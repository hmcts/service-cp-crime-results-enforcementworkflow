# Feature Specification: Enforcement Result Notification Email (CIMD-4548, CIMD-4543)

**Feature Branch**: `005-cimd-4548-4543-enforcement-result-notification`
**Created**: 2026-11-23
**Status**: Draft
**Input**: User description: "CIMD-4548 notification on share , CIMD-4543 notification re-share"

## Context

Epic [CIMD-2864](https://hmcts.atlassian.net/browse/CIMD-2864) ("Notify Enforcement and/or Confiscation team when
Enforcement case is resulted") is delivered as two stories that share one pipeline and differ only in eligibility,
template and subject line (design doc
[Enforcement Result Notifications](https://hmcts.atlassian.net/wiki/spaces/DATAIN/pages/367591581/Enforcement+Result+Notifications)):

- **[CIMD-4548](https://hmcts.atlassian.net/browse/CIMD-4548) — First share**: send an email with an "Enforcement
  Result Notification" PDF the first time an Enforcement/Confiscation case hearing is resulted and shared, when at
  least one captured result shortCode is on the team's notify-me list.
- **[CIMD-4543](https://hmcts.atlassian.net/browse/CIMD-4543) — Amend and reshare (A&R)**: send an email with an
  "Enforcement hearing result amended" PDF whenever a previously-shared Enforcement-type case result is amended and
  reshared, because **no `hearingResultRequest` is submitted to GOB on an A&R** (CIMD-4251/002 FR-016) — this email
  is the only notification channel the team gets when a result changes after the first share.

Both stories reuse the `POST /enforcement-notifications` trigger already delivered by feature
[003 (enforcement-nows-trigger)](../003-enforcement-nows-trigger/spec.md) from the results-processing Function App
(`EnforcementNotificationController` / `NotificationService`, already in this codebase) — this feature does not add
a new inbound endpoint. What's new is everything this service does **after** that call: team-inbox lookup, PDF
rendering, email dispatch and the resulting audit trail.

Two dependencies named only generically in the original design doc are now grounded in real, inspectable sources:

- **Email dispatch** goes through
  [`cp-gov-uk-notify-gateway`](https://hmcts.atlassian.net/wiki/spaces/PETTA/pages/308969906/cp-gov-uk-notify-gateway+Implementation+Technical+Overview),
  the Modern-by-Default re-platform of the legacy notify context, reached by an Azure Service Bus command on the
  shared `ng-send-email` queue — not a bespoke "cl/speient gateway" integration. This feature is only the gateway's
  second real client (after `cpp-context-mi-reportdata`'s fire-and-forget use); unlike that client, this feature
  needs a dispatch-confirmation event to satisfy FR-017/FR-018, which the gateway only publishes when the command
  supplies a `ReplyTo` queue.
- **PDF rendering** is delivered by `cpp-context-system-doc-generator` branch `team/CHD-2786` ("bring your own file
  store" / BYO-FS), not yet merged to `main` at time of writing. It adds a URI-addressed path to the existing
  `generate-document` command/query: a request may supply `payloadFileUri`/`destinationFileUri` (Azure Blob URIs
  this service owns) instead of the legacy `payloadFileServiceId`/`documentFileServiceId`, and the query API reports
  completion via `destinationFileUri`/`generatedTime`, or failure via `failedTime`/`reason`.

See [data-model.md](data-model.md), [research.md](research.md) and `contracts/` for the detail behind both, and the
new approach to sourcing the "previous share" content the amend-reshare template must display alongside the new
result.

## Clarifications

### Session 2026-11-24

- Q: FR-015 says duplicate emails must be guarded against for a duplicate event "received within a short window
  of the original" — is this a time-bounded definition, or does it mean a retry of the same upstream trigger
  call regardless of elapsed time? → A: Duplicate = a retry/resend of the *same* upstream trigger call (same
  trigger notification id from feature 003's endpoint) — no time limit. Two genuinely distinct trigger calls for
  the same hearing/reference, even seconds apart, are each a legitimate share.
- Q: FR-018's purge of "interim data" after delivery is confirmed — does this include the record of which result
  shortCodes/offences were matched into the email (needed by FR-009 to diff the *next* reshare against this one),
  or only the rendered document artifacts? → A: Purge applies only to the payload JSON and the rendered PDF
  (the two blob artifacts); the matched-results record (which shortCodes/offences were rendered) is never
  purged — it is retained as the audit trail and as the input to FR-009's comparison for the next reshare.
- Q: FR-009 requires diffing an amend-reshare's "original" side against "the immediately preceding share" — but
  Acceptance Scenario 3 describes a hearing whose first share was suppressed (no trigger shortCode, so no email
  was ever sent), meaning there is no previous share to diff against. Should the amend-reshare path's own
  shortCode-trigger gate (previously: none, per FR-011) be reinstated so that only a reshare whose *current* state
  includes a trigger shortCode produces an email? → A: Yes — **this reverses FR-011/FR-012's previous "no
  shortCode filter on reshare" rule.** The amend-reshare path now applies the same trigger-shortCode gate as
  first-share (FR-001), evaluated fresh against the hearing's current (post-amendment) state on every reshare —
  not based on whether the *original* first share qualified. Confirmed via three concrete sub-cases (all starting
  from "first share had only non-trigger shortCodes, suppressed, no email sent"): amending to **add** a new
  trigger shortCode → email IS sent (current state now qualifies); amending to add another non-trigger shortCode,
  or changing an existing non-trigger result's value → email is **NOT** sent (current state still has no trigger
  shortCode). This gate is independent of, and evaluated before, FR-012's separate "do not suppress a
  content-identical reshare" rule: the gate decides *whether* to send at all; FR-012 only governs *that a
  qualifying reshare is never skipped just because nothing changed*.
- Q: Does FR-017's "recoverable without re-running already-completed steps" require automatic retry, or is manual
  recovery (a support engineer re-triggering the failed step against the existing notification record)
  sufficient? → A: Manual recovery is sufficient for this feature; automatic retry/replay is out of scope here
  and is covered by a separate story.
- Q: Per FR-012, a reshare with no observable change from the previous share still produces an email (once
  FR-011's gate is met) — should the PDF explicitly indicate "no change from previous share" in that case, or is
  showing identical original/new content with no special flag sufficient? → A: No special indication needed;
  identical original/new columns are self-evidently "no change" — FR-008's existing content requirements are
  sufficient as written, no new requirement added.

### Session 2026-11-26

- Q: The Session 2026-11-24 clarification reversed FR-011/FR-012 to gate the amend-reshare path by the same
  trigger-shortCode list as first-share. Is that correct against the actual Jira ticket text? → A: **No —
  corrected.** CIMD-4543's acceptance criteria were fetched directly from Jira and are explicit: "No shortCode
  filtering applies on the A&R path... this differs from the first-share path, which continues to filter by the
  16-code first-share trigger set." AC4/AC5 amend a non-Enforcement-only hearing with no trigger shortCode
  involved at any point and still require **EMAIL SENT** — AC5's own rationale states this explicitly: "the
  reversal ensures the shortCode-filter gate is not applied on the A&R email." AC7 reshares a hearing previously
  shared under a non-trigger code (`ABDC`, never in the 16-code set, so first-share was silent) and also requires
  **EMAIL SENT** on reshare. This supersedes the 2026-11-24 answer in full: FR-011's trigger-shortCode gate
  applies to **first-share only**. The amend-reshare path has no shortCode gate at all — it fires on every
  amend-and-reshare event on a case already selected as in-scope Enforcement/Confiscation, regardless of which
  shortCodes are present, added, removed, or changed.
- Q: CIMD-4548/4543's acceptance criteria also require the rendered PDF to appear under the case's **Case
  Material** section in CP's UI (as "Electronic notification"), not only be emailed — this was missing from the
  spec entirely (a coverage gap found after initial implementation, not something previously scoped out). What
  `documentTypeId` does "Electronic notification" correspond to in reference data? → A: Confirmed —
  `f471eb51-614c-4447-bd8d-28f9c2815c9e` ("Electronic Notifications"), the same reference-data document type
  already reused platform-wide for this kind of electronic notice (e.g. ordinary hearing notifications in
  `cpp-context-progression`).
- Q: Does attaching the document to the case (`progression.command.add-court-document`) require a genuine,
  logged-in user context, the way a human UI action would? → A: No — this service calls Progression as its own
  registered system user (the same CJSCPPUID convention already used for every other outbound integration in
  this feature), not a forwarded human user id.
- Q: Is this service's own `ProsecutionCase.id()` (already carried on `HearingResultedEvent`) the same UUID
  identity-space as Progression's `prosecutionCaseId`? → A: Yes, confirmed — used directly, no translation.
- Q: Who allocates the `materialId` Progression's `add-court-document` command needs? → A: This service generates
  it itself (`UUID.randomUUID()`, the same convention the CPP reference implementation uses) and passes it on the
  URL/request; Progression's multipart `upload-court-document` call stores the PDF bytes into its own File Store
  and derives the file's own internal `fileServiceId` itself — this service never allocates or sees a
  `fileServiceId`.

## Terms

- **First share**: the first time a hearing result for an Enforcement/Confiscation case is shared with CP (feature
  002/003 `isReshare = false`).
- **A&R (amend and reshare)**: a previously-shared hearing result that is amended and resubmitted (`isReshare =
  true`); no GOB payload is sent for these.
- **relatedReferenceNumber**: the Enforcement case reference every Enforcement case submission must carry
  ([CIMD-4172](https://hmcts.atlassian.net/browse/CIMD-4172)); corresponds to this event's existing
  `prosecutionAuthorityReference` (feature 001 data-model). One notification email is sent per unique value of this
  reference on the hearing, not per hearing or per defendant.
- **Division Code**: the code used to resolve which team inbox (Enforcement or Confiscation) an Enforcement case
  belongs to, via the `enforcement_area` reference-data lookup ([CIMD-4389](https://hmcts.atlassian.net/browse/CIMD-4389)).
- **Trigger shortCode list**: the 16 result shortCodes that gate the **first-share** email only — `ACF`, `AEC`,
  `AEO`, `AEOC`, `CLAMPO`, `CW`, `CWN`, `FIDIC`, `FIDICT`, `FIDIP`, `FTTP`, `MPSO`, `REGF`, `S136`, `TFOOUT`, `WDN`
  for Enforcement, plus `BPOC` (and other Confiscation-scoped codes per the NOWS matrix) for Confiscation
  Division Codes `{45, 66, 97, 109, 110, 116}`. The amend-reshare path has no shortCode gate at all: it is scoped
  only by case-type eligibility (Enforcement/Confiscation), never by which shortCodes are present, added,
  removed, or changed (see FR-011; corrected 2026-11-26 against CIMD-4543's real acceptance criteria).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - First-share notification to the responsible team (Priority: P1)

As the Common Platform system, when an Enforcement or Confiscation case hearing result is shared for the first time
and at least one captured result shortCode is one the responsible team wants to be alerted about, an email with an
"Enforcement Result Notification" PDF is sent to that team's inbox, so they can act on the warrant/order without
delay.

**Why this priority**: This is the foundational trigger and the simplest of the two paths (one gating rule, one
template). Delivering it alone already gives Enforcement/Confiscation teams same-day visibility of new, actionable
results — the primary business value of the epic.

**Independent Test**: Share a hearing result on an Enforcement case where a captured result shortCode is in the
trigger list; verify exactly one email with the correct PDF is delivered to the correct team inbox, logged, and that
re-submitting the same share produces no second email.

**Acceptance Scenarios**:

1. **Given** a hearing on an Enforcement case (relatedReferenceNumber resolves to Division Code 010) with a trigger
   shortCode (e.g. `CW`) captured with user-entered prompts, **When** the result is shared for the first time,
   **Then** CP looks up the team inbox by Division Code, renders the PDF listing `CW` and its prompts, sends it via
   the team's email channel, and logs the delivery event with caseUrn, correlationId, recipientEmail, trigger
   shortCodes and Division Code.
2. **Given** a hearing with shortCodes `[SC, CW, ACNOTE]` on one relatedReferenceNumber, where only `CW` is a trigger
   shortCode, **When** shared for the first time, **Then** CP sends **one** email listing the trigger-eligible
   results present on the hearing, in the order they were captured (not alphabetically).
3. **Given** a relatedReferenceNumber that resolves to a Confiscation Division Code (e.g. 110) with a trigger
   shortCode (e.g. `BPOC`), **When** shared for the first time, **Then** CP sends the email to the Confiscation
   team's inbox instead of Enforcement's.
4. **Given** a hearing with only non-trigger shortCodes (e.g. `[ADJNN, PTNV]`), **When** shared for the first time,
   **Then** CP sends no email and logs no delivery event (other NOWS/bulk-print flows continue as normal).
5. **Given** a hearing previously shared with a trigger shortCode (its email already sent), **When** the user
   re-opens and resubmits the same share with no amendment, **Then** no second first-share email is sent.

---

### User Story 2 - Amend-and-reshare notification with before/after comparison (Priority: P1)

As the Common Platform system, when a previously-shared Enforcement-type case hearing result is amended and
reshared, an email with an "Enforcement hearing result amended" PDF — showing the original result alongside the new
one — is sent to the responsible team, regardless of which shortCode(s) are present on, added to, removed from, or
amended on the reshared hearing, so they see the current position even though no GOB payload reaches them for
this change.

**Why this priority**: Equally critical to User Story 1: because an A&R never produces a GOB submission, this email
is the *only* way the team learns a result changed. Without it, a team could act on a stale result indefinitely.

**Independent Test**: Share a hearing result once, then amend and reshare it; verify exactly one amend-reshare email
is sent with a PDF correctly comparing the original and new results, regardless of which shortCode(s) changed.

**Acceptance Scenarios**:

1. **Given** a hearing on an Enforcement case previously shared with a trigger shortCode result (e.g. `resultCode =
   CW`, `jailDays = 2 weeks`), **When** the user amends a prompt value (`jailDays` → 3 weeks) and reshares, **Then**
   CP sends an amend-reshare email to the team's inbox with subject "...: Enforcement hearing result amended" and a
   PDF showing the original value alongside the new one.
2. **Given** a hearing previously shared with both an Enforcement-triggering result (e.g. `CW`) and a
   non-triggering one (e.g. `ACNOTE`), **When** the user amends only the non-triggering result's prompt and
   reshares, **Then** CP still sends an amend-reshare email showing both results' current state.
3. **Given** a hearing previously shared with only non-trigger shortCodes (no first-share email was sent), **When**
   the user amends it in any way — adding a trigger shortCode, adding another non-trigger shortCode, or changing an
   existing non-trigger result's value — **Then** CP sends an amend-reshare email in every case; the amend-reshare
   path has no shortCode gate (FR-011 applies to first-share only). This also covers the case where the amendment
   changes a field (e.g. `enforcerCode`) on a shortCode that was never in the trigger list at all (CIMD-4543 AC7).
4. **Given** a Confiscation-type case previously shared with any result, **When** amended and reshared, **Then** the
   email routes to the Confiscation team's inbox via the same Division Code lookup used for first share.
5. **Given** CP's REST push of the (unrelated) GOB payload fails independently, **When** the hearing is subsequently
   amended and reshared, **Then** the amend-reshare email is still dispatched; the GOB failure is logged and
   replayed separately and does not block the email, nor does the email path block the GOB replay.
6. **Given** three successive amendments on the same hearing, **When** each is reshared, **Then** CP sends three
   separate emails, each one comparing against its own immediately-preceding share (not the original first share).

---

### Edge Cases

- Division Code resolves to no match in reference data → log "Inbox not found for Division Code {x}", record the
  outcome, send no email; the hearing capture is never blocked (applies to both paths).
- A PDF render or email-dispatch failure for one relatedReferenceNumber must not affect any other
  relatedReferenceNumber on the same hearing, nor the (independent) GOB submission.
- PDF attachment over 2 MB uses the existing Office365 fallback delivery convention instead of GOV.UK Notify.
- Welsh-flagged NOWS templates render the PDF attachment in Welsh for bilingual results; the email body stays
  English by default.
- A duplicate share/reshare event — the same upstream trigger call delivered more than once (user double-click,
  upstream retry), at any elapsed time — must not produce duplicate emails (FR-015).
- A reshare that changes nothing observable from the previous share still produces an amend-reshare email
  (FR-012); this is distinct from a duplicate event (above), which is the same share arriving twice and must not
  double-send.
- A failure attaching the PDF to Case Material (FR-020) must not affect email dispatch, and a failure sending the
  email must not affect the Case Material attachment — the two are logged and recovered independently.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: On a first share of an Enforcement or Confiscation case hearing result, the system MUST check whether
  any captured result shortCode is in the trigger shortCode list (see Terms) for that case's area (Enforcement or
  Confiscation).
- **FR-002**: When FR-001 matches, the system MUST send exactly **one** notification email per unique
  relatedReferenceNumber on the hearing to the team inbox resolved for that case's Division Code.
- **FR-003**: The system MUST resolve the team inbox (Enforcement or Confiscation area, team email address) from the
  case's Division Code via the reference-data lookup, treating Division Codes `{45, 66, 97, 109, 110, 116}` as
  Confiscation and all other resolved Division Codes as Enforcement.
- **FR-004**: The first-share email MUST carry an "Enforcement Result Notification" PDF listing every matched
  trigger shortCode on the hearing together with its user-entered prompt values, in the order the results were
  captured on the hearing.
- **FR-005**: The system MUST NOT send a first-share email when no captured shortCode is in the trigger list, and
  MUST NOT send a second first-share email for a hearing that already produced one (single-fire per hearing;
  idempotent on an unmodified resubmission of the same share).
- **FR-006**: When the Division Code lookup finds no matching team inbox, the system MUST log the failure, send no
  email, and MUST NOT block or delay the hearing capture.
- **FR-007**: On an amend-and-reshare of a previously-shared case hearing result, the system MUST send exactly one
  notification email per unique relatedReferenceNumber to the resolved team inbox, for every such event on an
  in-scope Enforcement/Confiscation case — see FR-011.
- **FR-008**: The amend-reshare email MUST carry an "Enforcement hearing result amended" PDF showing the original
  result alongside the new result for each offence, the amendment reason, the original date of sentence, the
  amendment date, and the case/defendant identification.
- **FR-009**: The system MUST diff each amend-reshare email's "original" side against the immediately preceding
  share of the same hearing, not against the original first share, so a chain of amendments produces one accurate
  comparison per step.
- **FR-010**: Email dispatch (first-share or amend-reshare) MUST run independently of the CP→GOB payload submission:
  a failure in one path MUST NOT block or be blocked by the other, and both outcomes MUST be logged separately.
- **FR-011**: The first-share trigger-shortCode gate (FR-001) applies **only** to the first-share path. The
  amend-reshare path MUST NOT apply any shortCode gate: an amend-reshare email MUST be sent for every
  amend-and-reshare event on a case already selected as in-scope Enforcement/Confiscation, regardless of which
  shortCodes are present, added, removed, or changed — including when the amendment touches only non-trigger-list
  shortCodes or non-Enforcement result prompts (CIMD-4543 AC4/AC5/AC7). (Corrected 2026-11-26: the 2026-11-24
  clarification's reversal of this rule was made without checking the real Jira acceptance criteria and is wrong
  — see Clarifications, Session 2026-11-26.)
- **FR-012**: The amend-reshare path MUST NOT suppress any in-scope amend-and-reshare event, whether or not its
  content differs from the immediately-preceding share (no content-diff suppression) and regardless of shortCode
  involvement.
- **FR-013**: A PDF attachment larger than 2 MB MUST be delivered via the existing Office365 fallback path instead of
  the primary email-notification channel.
- **FR-014**: The system MUST render the PDF attachment in Welsh when the matched result's NOWS template is
  Welsh-flagged, while the email body itself defaults to English.
- **FR-015**: The system MUST guard against producing duplicate emails when the same upstream trigger call (same
  trigger notification id from feature 003's endpoint) is received more than once, regardless of the elapsed time
  between deliveries; two genuinely distinct trigger calls for the same hearing and relatedReferenceNumber are
  each a legitimate share and MUST each produce their own email.
- **FR-016**: Every notification outcome (sent, suppressed, or failed) MUST be logged with the caseUrn, the
  resolved Division Code, the recipient team email, the triggering/changed result shortCodes, the sharing user's
  identifier, and an ISO-8601 UTC timestamp. For a `SUPPRESSED` outcome specifically, where by definition no
  shortCode matched the trigger list, this field MUST log the full set of shortCodes present on the hearing's
  current state at the time of evaluation (not an empty array) — so the log line shows what was reviewed and
  found non-qualifying, not merely that nothing qualified.
- **FR-017**: A rendering or dispatch failure for either email type MUST be recoverable without re-running
  already-completed steps for that notification, and MUST NOT require the hearing itself to be resubmitted.
  Manual recovery (re-triggering the failed step against the existing notification record) satisfies this
  requirement; automatic retry/replay is out of scope for this feature and is addressed by a separate story.
- **FR-018**: Once a notification email is confirmed delivered, the system MUST remove the interim document
  artifacts it created to produce that notification (the render payload and the rendered PDF); nothing MUST be
  removed before delivery is confirmed. The record of which result shortCodes/offences were matched into the
  notification MUST NOT be removed — it is retained as the audit trail and as the "immediately preceding share"
  input FR-009 requires for the next amend-reshare's comparison.
- **FR-019**: Bulk-print and other existing NOWS handling for the hearing MUST be unaffected by, and run independent
  of, this email-notification capability.
- **FR-020**: In addition to emailing it, the system MUST attach the rendered notification PDF to the case's
  **Case Material** section in CP (as "Electronic notification", reference-data `documentTypeId`
  `f471eb51-614c-4447-bd8d-28f9c2815c9e`) for both the first-share and amend-reshare paths. This attachment MUST
  run independently of email dispatch (FR-010's independence principle extends to this step too): a case-material
  attachment failure MUST NOT block, delay, or be implied by the email outcome, and vice versa, and each MUST be
  logged and recoverable separately (FR-017).

### Key Entities

- **Team Inbox Mapping**: the result of resolving a case's Division Code to a notification area (Enforcement or
  Confiscation) and a team email address, via the external `enforcement_area` reference-data lookup.
- **Notification Record**: the per-relatedReferenceNumber, per-share audit trail of a notification attempt — its
  outcome (sent / suppressed / inbox-not-found / failed), the resolved inbox, the triggering or changed shortCodes,
  and the sharing user and timestamp.
- **Enforcement Result Notification PDF**: the first-share document listing every matched trigger shortCode and its
  prompts.
- **Amendment Notification PDF**: the amend-reshare document comparing the original and new result by offence,
  with amendment reason and dates.
- **Trigger ShortCode List**: the configurable set of result shortCodes (16 Enforcement codes, plus the
  Confiscation-scoped codes per the NOWS matrix) that gates the first-share email only (FR-001/FR-011); the
  amend-reshare email has no shortCode gate.
- **Previous Share Snapshot**: the minimal record of what was captured and sent on a hearing's immediately-preceding
  share (hearingId, hearingDate, relatedReferenceNumber, and the matched results/prompt values used to render that
  share's PDF), kept by this feature so the next amend-reshare's "original" comparison column does not depend on
  the Results cache or Results context still holding that earlier state (see data-model.md).
- **Case Material Attachment**: the record of attaching the rendered notification PDF to the case's Case Material
  section in CP (FR-020) — a `materialId` this service itself generates, the `courtDocument` attached via
  `progression.command.add-court-document` under the "Electronic Notifications" document type, and its own
  independent outcome/status (see data-model.md).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of first-share hearing results carrying a trigger shortCode produce exactly one notification
  email to the correct team inbox, with no duplicates.
- **SC-002**: 100% of amend-and-reshare events on a previously-shared Enforcement/Confiscation case produce exactly
  one notification email, independent of whether the GOB payload submission for that case succeeds or fails.
- **SC-003**: Enforcement/Confiscation teams can tell exactly what changed in an amendment from the emailed PDF
  alone, without needing to open the case in CP.
- **SC-004**: No hearing capture is ever blocked, delayed or rejected because of a notification lookup, rendering,
  or dispatch failure.
- **SC-005**: A rendering or dispatch failure for one relatedReferenceNumber never prevents another
  relatedReferenceNumber's notification, on the same hearing, from being delivered.

## Assumptions

- This feature is delivered entirely behind the existing `POST /enforcement-notifications` trigger from feature 003
  (already implemented in this codebase); no new inbound endpoint, event subscription, or Function App change is
  required of this service for the trigger itself.
- The `enforcement_area` reference-data lookup (Division Code → area type, team email; CIMD-4389) is delivered and
  maintained by another team; this feature only integrates against it and treats its content (trigger shortCode
  list, Division Code set, team emails) as externally-owned configuration, not something this feature defines.
- PDF rendering depends on the BYO-FS (bring your own file store) capability in `cpp-context-system-doc-generator`
  branch `team/CHD-2786`, not yet merged to `main` at time of writing; this feature's own PDF rendering cannot go
  live before that capability ships to `main` and is deployed.
- Email dispatch (GOV.UK Notify, with an Office365 fallback above 2 MB) is performed by `cp-gov-uk-notify-gateway`
  via an Azure Service Bus command on the `ng-send-email` queue, not by this service directly; this service only
  requests dispatch and observes the result. Because the gateway only emits a result event when the inbound command
  carries a `ReplyTo` queue, this feature must supply one (unlike the gateway's only other current client, which is
  fire-and-forget) in order to know when a notification is actually delivered, as FR-017/FR-018 require.
- The "original" side of an amend-reshare comparison (FR-008/FR-009) cannot be sourced from the Results cache or
  Results context query API alone, because both only ever expose the hearing's *current* state (the cache key is
  overwritten on each share and has a 24h TTL; the query API returns the latest hearing day only) — so this feature
  persists its own Previous Share Snapshot at the time each share's email is successfully dispatched, and reads the
  *current* state via the existing cache-then-query pattern from feature 003 (hearing_id + hearing_date keyed Results
  cache lookup, falling back to the Results context query API on a miss) for the "new" side and for first-share
  content.
- Welsh-template rendering and the >2 MB delivery fallback reuse conventions already established for other NOWS
  documents in this service; no new Welsh-translation or attachment-size logic is introduced here.
- Multiple-defendant cases, one-hearing-with-multiple-cases, and cases sharing a defendant but differing
  relatedReferenceNumber are out of scope for both stories (explicitly excluded in both source tickets).
- The Case Material attachment (FR-020) is performed by this service calling `cpp-context-progression`'s own
  `progression-command-api` REST tier directly (`POST /courtdocument/{materialId}`), authenticating as this
  service's own registered system user (CJSCPPUID), the same pattern used for every other outbound integration
  in this feature — not via raw internal JMS commands, which this service has no existing precedent for sending.
  This requires a new system user to be registered and allow-listed on Progression's side before go-live (see
  plan.md dependencies), the same shape as the existing `enforcement_area` dependency.
