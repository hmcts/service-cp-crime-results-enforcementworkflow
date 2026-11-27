# Research: Enforcement Result Notification Email (feature 005)

Decisions referenced as R* are used by [data-model.md](data-model.md) and `contracts/`.

## R1 — Email dispatch goes through `cp-gov-uk-notify-gateway`, not a generic "client gateway"

Source: [cp-gov-uk-notify-gateway — Implementation Technical Overview](https://hmcts.atlassian.net/wiki/spaces/PETTA/pages/308969906/cp-gov-uk-notify-gateway+Implementation+Technical+Overview)
(PETTA space, PEG-3350) and the `hmcts/cp-gov-uk-notify-gateway` repo (`contracts/*.schema.json`, `docs/pipeline/adrs/`).

- `cp-gov-uk-notify-gateway` is the Modern-by-Default re-platform of the legacy WildFly
  `cpp-context-notification-notify`. It is the strategic replacement, not a parallel service — the legacy context is
  retired only once every originator context has migrated.
- **Inbound**: a flat JSON command on the shared Azure Service Bus queue `ng-send-email` (one queue per command
  type, not per client). Required fields `{notificationId, templateId, sendToAddress}`; optional `fileUri` (Azure
  Blob, BYO filestore, managed identity) or `materialUrl` (legacy Material service link; a non-blank `materialUrl`
  takes precedence over `fileUri`); `replyToAddress`/`replyToAddressId`; `personalisation`; `clientContext` (an
  opaque passthrough, not a routing key). `notificationId` is the idempotency key (PK dedupe on ingest).
- **`ReplyTo` is a native ASB message property, and is required.** A command without one is dead-lettered unread. A
  command that fails schema validation is answered with a `notification-validation-failed` event on `ReplyTo`
  instead of being processed.
- **Outbound result events**, routed to the `ReplyTo` queue only (never broadcast to a topic). **Correction**: an
  earlier draft of this section used the legacy public-topic naming convention
  (`public.notificationnotify.events.*`, the pattern for genuinely topic-broadcast events like
  `public.systemdocgenerator.events.document-available`) — confirmed wrong by reading the gateway's own schema
  files directly (`contracts/notification-sent.schema.json`'s `$id`/`title` is just `notification-sent`, no such
  prefix) and by the working `cpp-context-staging-dvla` consumer (`NotifyGatewayResultService`), which matches
  the ASB message's `subject` property against the bare strings. The event is a direct, point-to-point reply on
  the `ReplyTo` queue, never a topic broadcast. The three ASB `subject` values this service must match on:
  - `notification-sent` (`additionalProperties:false`; `notificationId`, `sentTime` required; also carries
    `completedAt`, `sendToAddress`, `replyToAddress`, `emailSubject`, `emailBody`, `clientContext`)
  - `notification-failed` (`notificationId`, `failedTime`, `errorMessage` required; also `statusCode`,
    `clientContext`)
  - `notification-validation-failed` — sent instead of either of the above when the inbound command carries a
    `ReplyTo` but fails this schema's own validation; the rejected command's own body is echoed back
    (`originalEventPayload`) for correlation, per `cpp-context-staging-dvla`'s own handling of it. A command that
    lacks `ReplyTo` entirely is dead-lettered unread instead — no event reaches this service at all in that case,
    which is why this service must always supply one.
- **MVP has no consumer**: the only current client, `cpp-context-mi-reportdata`, is fire-and-forget and sets no
  `ReplyTo`, so no result event is ever consumed today. This feature would be the gateway's **first consumer of its
  own result events** — needed to satisfy FR-017 (recoverable failure) and FR-018 (purge only after confirmed
  delivery), which depend on actually observing `notification-sent`/`notification-failed` rather than assuming
  success once the command is accepted.
- **Idempotent send**: the gateway's own send task checks GOV.UK Notify by client `reference` (=`notificationId`)
  before sending, so a redelivered command or task re-run does not double-send (a fix over legacy, which has no such
  guard). Combined with PK dedupe on ingest, this feature does not need its own additional de-duplication layer in
  front of the gateway for the email step itself — only for the trigger event, per FR-015.
- **Error taxonomy** (ported from legacy `FailureSelector`): permanent (HTTP 400/413, Blob 403/404 → fail fast, no
  retry) vs. transient (HTTP 500, `statusCode==0` classes → retried on a fixed backoff schedule, externalised to
  config). This feature's own EMAIL_FAILED handling (FR-017) should treat a `notification-failed` event the same
  way: a permanent failure is terminal and surfaced, a transient one may still resolve on the gateway's own retry
  before this feature needs to intervene.

## R2 — PDF rendering: BYO-FS on `cpp-context-system-doc-generator` branch `team/CHD-2786`

Source: `hmcts/cpp-context-system-doc-generator`, branch `team/CHD-2786` (not yet merged to `main`), specifically
`systemdocgenerator-query/systemdocgenerator-query-api/src/raml/json/schema/systemdocgenerator.query.document.json`
and `systemdocgenerator-integration-test/.../GenerateDocumentWithUriIT.java`.

- BYO-FS adds a URI-addressed path to the existing `generate-document` command and its query API, alongside (not
  replacing) the legacy `payloadFileServiceId`/`documentFileServiceId` (Material-service-id) path — the query
  schema's `oneOf` requires exactly one of `payloadFileServiceId` or `payloadFileUri`.
- **Request** (`generate-document` command): `templateIdentifier`, `conversionFormat`, `requestedTime` always
  required; **either** `payloadFileServiceId` **or** `payloadFileUri` (an Azure Blob URI this service owns, holding
  the Docmosis payload JSON this feature must write there first); optionally `destinationFileUri` (the Azure Blob
  URI the rendered PDF is written back to, instead of a generated `documentFileServiceId`).
- **Query response**: `documentFileServiceId`/`destinationFileUri` + `generatedTime` on success, or `failedTime` +
  `reason` on failure. When a request was addressed by `payloadFileUri` alone (no `payloadFileServiceId`), the
  query key (document id) is `UUID.nameUUIDFromBytes(payloadFileUri)` — a deterministic id derived from the URI, not
  a server-issued one.
- Both the request and response blob URIs are read/written via managed identity (no SAS), consistent with this
  repo's "no connection strings/SAS/keys" hard rule.
- **Go-live gate** (carried over from the original design doc, now concrete): this feature's PDF rendering cannot
  deploy before `team/CHD-2786` merges to `main` and is released; track this branch, not a generic "unreleased
  dependency".

## R3 — Sourcing the "previous share" content for the amend-reshare comparison

Source: this repo, feature 003 `contracts/results-data-sources.md` and `ResultsCacheClient`
(`src/main/java/uk/gov/hmcts/cp/client/ResultsCacheClient.java`), re-examined against this feature's FR-008/FR-009
need to show the *original* result alongside the *new* one.

- The existing Results cache (`INT_{hearingId}_{hearingDate}_result_`, 24h TTL, Azure Cache for Redis) and the
  Results context query API (`GET .../hearingDetails/internal/{hearingId}`, "latest hearing day") both exist **only
  to answer "what is the hearing's current state"** — used by feature 003 to find out whether a hearing has in fact
  been resulted. Neither retains history: the cache key is overwritten on every new share of the same hearing/date,
  and the query API is explicitly documented as returning the latest hearing day only.
- Consequence: this feature can reuse that same cache-then-query sequence to fetch the **current** state (the "new"
  side of a comparison, and the only side needed for a first-share email) but **cannot** use it to recover what a
  hearing looked like before the most recent amendment — by the time this feature processes an amend-reshare
  notification, the cache/API already reflect the amended state.
- Decision: this feature persists its own **Previous Share Snapshot** (hearingId, hearingDate,
  relatedReferenceNumber, matched shortCodes/prompt values) at the point each share's email is successfully built,
  and reads that snapshot back — not the cache/API — for the "original" side of the next amend-reshare's comparison
  (data-model.md). The cache-then-query sequence is still the source of truth for the **current** state on every
  share, first or amended.

## R4 — Architecture: a separate, independent task chain (FR-010)

- **Decision**: add one line to `NotificationService.store()` (inside the existing transaction, next to today's
  `tasks.queueProcess(entity.getId())`): `resultNotificationTasks.queueResolve(entity.getId())`. This starts a
  second, fully independent `cp-task-manager` job chain, keyed off the same trigger notification id, with its own
  entity, retry/replay, and no shared failure path with the GOB chain.
- **Rationale**: `PublishGobDataTask` only runs for hearings that reach `isReadyToPublish()` in stage 1 — an
  amend-and-reshare with nothing new for GOB never reaches it at all, yet FR-012 requires it to always produce an
  email (subject to the FR-011 gate, R9). So the email pipeline cannot be chained after the existing GOB stage 2;
  it must be queued independently. The cost is one duplicated `HearingResultsSource.sharedResults(...)` call per
  share — accepted as the right trade-off given the independence requirement.
- **Alternatives considered**: chaining a new task onto `PublishGobDataTask`'s existing job row (rejected — ties
  this feature's failure/retry path to the GOB chain's, violating FR-010 literally for the amend-reshare case
  that never reaches `PublishGobDataTask` at all).

## R5 — One id, reused end-to-end

- **Decision**: `enforcement_result_notification.id` (an app-generated UUID) is the single id that flows through
  this entire pipeline — it is both the `sourceCorrelationId` sent to the doc generator and the `notificationId`
  sent to `cp-gov-uk-notify-gateway`. No separate `gov_notify_notification_id` column.
- **Rationale**: the Confluence design doc is explicit (step 7, "Creates a notificationId"; that same id is
  `sourceCorrelationId` on the doc-generator command and `notificationId` on the gateway command) — there is only
  one id per row, not two.
- **Alternatives considered**: a separate `gov_notify_notification_id` column generated at the email-dispatch
  step (an earlier draft of `data-model.md` did this) — rejected once the design doc's own wording was re-read
  carefully; it would have made correlating the two external calls back to one row harder, not easier.

## R6 — Division Code is derived locally, not a new field or lookup output

- **Decision**: Division Code is a prefix CP derives itself from `relatedReferenceNumber`
  (= `prosecutionAuthorityReference`), parsed as an **integer** (the Confiscation set mixes 2- and 3-digit
  values, so string comparison would be wrong), e.g. `"0100104567C"` → prefix `"010"` → `10`.
- **Rationale**: found in this repo's own `specs/002-enforcement-nows-generation/spec.md:34`: "emails to the
  Enforcement or Confiscation team are chosen by the MET ID prefix of the case's related reference number (equal
  to CP's Division Code)." This resolves what was the Plan agent's biggest open question — no field search of
  `HearingResultedEvent` or the `enforcement_area` response was ever going to find it, because it isn't there.
- **Alternatives considered**: Division Code as a new field added to `HearingResultedEvent` (rejected — no such
  field exists on the wire, confirmed by reading the event's full schema); Division Code computed/returned by the
  `enforcement_area` lookup itself (rejected — that lookup's job is Division Code → team inbox, not
  reference-number → Division Code). **Open item (D5 in plan.md)**: exact prefix length, assumed 3 characters,
  not yet confirmed against CIMD-4172.

## R7 — Per-offence capture and the current+previous payload design

- **Decision**: `result_short_codes` is a JSON array grouped by `offenceId` (not a flat list), each offence
  entry carrying `offenceTitle`/`offenceDetails` (new, additive fields on `HearingResultedEvent.Offence` — see
  R6a below) and a `shortCodes` array of `{judicialResultId, shortCode, resultLabel, orderedDate, prompts}`. The
  `payloadFileUri` JSON sent to the doc generator pre-merges current and previous per offence with a computed
  `changed` flag, rather than handing the template two parallel lists to diff itself.
- **Rationale**: an amend-reshare often changes the result for only *one* offence out of several on the same
  case — diffing by `offenceId` lets the template show which specific offence changed. Docmosis templates are
  hard to unit test, so the diff logic belongs in Java this service already tests, not template logic.
  `ResolvedResult(offenceId, judicialResultId, judicialResultTypeId, cpShortCode, gobCode, adjournmentMarker,
  orderedDate, prompts)` — the existing per-result resolution output used today for the GOB pipeline — already
  carries `offenceId` directly, so grouping by offence is `groupingBy(ResolvedResult::offenceId)` over a list this
  service already produces elsewhere, not a new resolution step.
- **R7a — shared event-model extension (additive)**: the upstream Results API already returns `offenceTitle`,
  `wording`, and per-judicial-result `resultText` on the wire (confirmed in
  `wiremock/mappings/results-hearing-details.json`), but `HearingResultedEvent.Offence`/`JudicialResult` are
  declared `@JsonIgnoreProperties(ignoreUnknown = true)` and silently drop them today. Decision: extend
  `Offence` with `offenceTitle`/`offenceDetails` (← wire `offenceTitle`/`wording`) and `JudicialResult` with
  `resultLabel` (← wire `resultText`, chosen over the already-captured `label` or reference-data's generic
  result-type label as the richer, instance-specific description). This is a shared domain-model change (used by
  the GOB pipeline too) but purely additive — existing consumers are unaffected.
- **Alternatives considered**: a flat (non-offence-grouped) `result_short_codes` array (rejected — loses the
  ability to diff per offence, the literal requirement); diffing in the Docmosis template instead of Java
  (rejected — untestable); a new reference-data lookup for offence title/wording (rejected — the data is already
  on the wire via the existing Results API, confirmed by inspecting its raw fixture, so no new integration is
  needed, only wider Jackson capture).

## R8 — Blob storage: single container, path-prefixed, `DefaultAzureCredential`

- **Decision**: one Blob container (not two), owned by this service, with payload and destination blobs
  distinguished by path prefix: `result-notification/requests/{id}` and `result-notification/documents/{id}.pdf`.
  Auth is plain `DefaultAzureCredentialBuilder()` unconditionally for every real environment (Workload Identity
  on AKS); `azurite-enabled` (default `false`) switches to `connection-string`/SharedKey only for local dev/IT,
  since Azurite has no Azure AD support.
- **Rationale**: confirmed directly against two sibling implementations' own code, not inferred: (1)
  `cpp-context-system-doc-generator` branch `team/CHD-2786`'s `AzureFileStoreBlobConfiguration` Javadoc states
  "staging-dvla is a producer that owns exactly one container, configured by `azure.filestore.container-name`";
  (2) `cpp-context-staging-dvla` branch `team/CHD-2786`'s own `AzureFileStoreBlobConfiguration` /
  `AzureFileStoreBlobContainerClientProducer` / `DocumentGeneratorService.uploadDocumentToAzureBlob` confirm the
  exact mechanism: a single `BlobContainerClient` produced once at startup (`createIfNotExists()`), with
  `PAYLOAD_BLOB_FOLDER`/`DOCUMENT_BLOB_FOLDER` path-prefix constants distinguishing the two blob kinds, and
  `DefaultAzureCredentialBuilder()` with no client-id branch for the credential. `destination_file_uri` is never
  uploaded to by this service — only its future URL is computed and handed to `generate-document`; the
  doc-generator writes the PDF there directly.
- **Alternatives considered**: two separately-configured containers, one per blob kind (an earlier draft) —
  rejected once the actual sibling implementations were read directly, since neither uses this shape; mirroring
  `ResultsCacheConfig`'s Redis credential pattern (`ManagedIdentityCredentialBuilder` + optional client id) for
  Blob too (an earlier draft) — rejected, since the real Blob precedent in this organisation uses a different,
  simpler credential builder with no client-id branch at all.

## R9 — Reshare trigger-shortCode gate (FR-011 reversal, Clarification 2026-11-24)

- **Decision**: the amend-reshare path applies the **same** trigger-shortCode gate as first-share (FR-001),
  re-evaluated fresh against the hearing's current (post-amendment) state on every reshare — not based on
  whether the *original* first share qualified. A reshare whose current state has no trigger shortCode produces
  no email at all, even though `isReshare = true`.
- **Rationale**: this reverses the feature's original design ("the amend-reshare path MUST NOT filter by result
  shortCode"), surfaced as a genuine conflict during `/speckit.clarify`: FR-009 requires diffing against "the
  immediately preceding share," which is undefined when the first share was itself suppressed (no trigger
  shortCode, no email ever sent) — User Story 2's own Acceptance Scenario 3 described exactly this case.
  Confirmed via three concrete sub-cases, all starting from "first share suppressed": amending to **add** a
  trigger shortCode → email now sent; amending to add a non-trigger shortCode, or changing an existing
  non-trigger value → still no email. This is independent of FR-012's separate "don't suppress a no-op reshare"
  rule, which only applies once the FR-011 gate is already satisfied.
- **Alternatives considered**: keep the original "no filter on reshare, ever" rule and treat the "no prior
  share" case as rendering an empty "original" column (the design's first attempt) — rejected by the user as
  not matching the actual business intent once Acceptance Scenario 3's concrete sub-cases were walked through.
  **Downstream correction needed**: `MatchResultNotificationTask`'s AMEND_RESHARE branch (previously designed as
  "no filter, always proceeds") and the `SUPPRESSED` state's scope (previously FIRST_SHARE-only) must both be
  widened to apply to AMEND_RESHARE too — reflected in `data-model.md`, not yet reflected in code.

## R10 — Idempotency key correction

- **Decision**: `UNIQUE (trigger_notification_id, related_reference_number)`, not
  `UNIQUE (hearing_id, hearing_date, related_reference_number, notification_type)`.
- **Rationale**: the rejected key would incorrectly block legitimate successive amend-reshares on the same
  hearing (spec.md's own User Story 2 Acceptance Scenario 6: three successive amendments, three separate
  emails) — they share all four of those values and would be wrongly blocked after the first. The corrected key
  ties to the *upstream trigger call*, not the hearing, so it only blocks a duplicate/retried fan-out of the
  *same* trigger (FR-015, Clarification 2026-11-24: duplicate = same trigger call id, no time window) while
  allowing unlimited legitimate distinct reshares over time.
- **Alternatives considered**: the hearing/date/reference/type key (an earlier draft, found to be a genuine bug
  before any code was written, by cross-checking against Acceptance Scenario 6).

## R11 — Manual-only recovery for FR-017 (Clarification 2026-11-24)

- **Decision**: a rendering/dispatch failure is recoverable by a support engineer manually re-triggering the
  failed step against the existing notification record. No automatic retry/replay sweeper is built by this
  feature.
- **Rationale**: confirmed via `/speckit.clarify` — automatic retry is covered by a separate story, out of scope
  here. This is consistent with (not forced by) constitution Principle V, which *permits* automatic retry when
  the external system is confirmed idempotent (the gateway's send step is) but does not require it. It also
  matches this codebase's existing reality: feature 003's own equivalent mechanism
  (`findByStatusAndNextReplayAtLessThanEqual`) has zero callers today — this feature is not introducing a new gap
  relative to the status quo, just not closing a pre-existing, shared one.
- **Alternatives considered**: building `ResultNotificationReplaySweeper` now (an earlier draft's assumption) —
  rejected once the user confirmed a separate story already covers this.

## R12 — Testing strategy: 100% unit coverage, exactly 4 integration tests

- **Decision**: every new class gets a full unit test suite (100% coverage, no exceptions); exactly one positive
  end-to-end integration test, plus three integration tests for distinct failure points (doc-generation failure,
  email-send failure post-acceptance, email validation-rejection failure).
- **Rationale**: the three failure-point tests map onto genuinely distinct failure mechanisms discovered this
  session, not three arbitrary variations: `generation-failed` (doc-generator rejects the render),
  `notification-failed` (the gateway accepted the command but the send itself failed),
  `notification-validation-failed` (the gateway rejected the command outright before attempting to send — a
  gap found only by reading `cpp-context-staging-dvla`'s own `NotifyGatewayResultService`, not originally in
  this design). `SUPPRESSED`/`INBOX_NOT_FOUND` are covered as unit tests on the tasks that produce them, not as
  separate integration tests, since they are pre-document-creation branch points, not failures of the
  document-creation/email-sending flow itself.
- **Alternatives considered**: covering `SUPPRESSED`/`INBOX_NOT_FOUND` as integration tests too (rejected as
  redundant with their unit-test coverage, and outside the "failure points while document creation and while
  sending the email" scope the user asked for).
