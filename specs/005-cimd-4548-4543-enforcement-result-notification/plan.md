# Implementation Plan: Enforcement Result Notification Email (CIMD-4548, CIMD-4543)

**Branch**: `dev/cct1222-email-notification` (branch naming deviates from `005-…`; branches managed by the
developer — see Complexity Tracking) | **Date**: 2026-11-24 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/005-cimd-4548-4543-enforcement-result-notification/spec.md`, with
Clarifications 2026-11-24 (5 resolved) and Clarification 2026-11-26 (corrects the 2026-11-24 FR-011/FR-012
reshare-gating reversal, which was made without checking the real Jira ACs and is confirmed wrong against
CIMD-4543's actual acceptance criteria, fetched directly 2026-11-26) and `checklists/pre-plan.md`. Builds on the
design doc *Enforcement Result Notifications* (DATAIN space, page 367591581) and research into
`cp-gov-uk-notify-gateway`, `cpp-context-system-doc-generator` branch `team/CHD-2786`, and
`cpp-context-staging-dvla` branch `team/CHD-2786` (the closest working reference implementation of almost this
same integration chain).

## Summary

CIMD-4548 (first share) and CIMD-4543 (amend-and-reshare) require this workflow to email the
Enforcement/Confiscation team a PDF whenever a relevant case hearing result is shared or reshared — because an
amend-and-reshare never produces a GOB submission, this email is the team's only notification channel for a
changed result. This feature adds **no new inbound endpoint**: it hooks into feature 003's existing
`POST /enforcement-notifications` trigger by queuing a second, fully independent `cp-task-manager` job chain
(FR-010), resolves the team inbox via a locally-derived Division Code and the `enforcement_area` reference-data
lookup, renders a PDF via `cpp-context-system-doc-generator`'s BYO-FS path (branch `team/CHD-2786`, unmerged —
go-live gate), and dispatches email via `cp-gov-uk-notify-gateway` over Azure Service Bus, observing its result
on a dedicated reply queue this service owns. The reshare path has **no** shortCode gate (confirmed against
CIMD-4543's real Jira acceptance criteria, Clarification 2026-11-26) — only the first-share path is gated by the
trigger-shortCode list; the reshare path is scoped by case-type eligibility alone.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.x, Gradle (unchanged — existing repo conventions).
**Primary Dependencies**: Existing: Spring Web, Spring Data JPA, Flyway, Jackson, Spring JMS/Artemis (already a
consumer, reused for `DocGeneratorEventListener`), `uk.gov.hmcts.cp` `cp-task-manager`, Spring `RestClient`.
**New**: `com.azure:azure-storage-blob`, `com.azure:azure-messaging-servicebus` (explicit pinned versions, not a
BOM — matching how `azure-identity:1.18.1` is pinned today). This feature is the first consumer of both in this
repo, though not in the organisation (`cpp-context-staging-dvla` branch `team/CHD-2786` already does almost
exactly this).
**Storage**: PostgreSQL (existing instance) — new table `enforcement_result_notification`
(`V1.010__create_enforcement_result_notification.sql`). Azure Blob Storage (new) — **one** container
(`DefaultAzureCredential`/Workload Identity, no SAS/connection strings in any real environment), path-prefixed:
`result-notification/requests/{id}` (payload JSON) and `result-notification/documents/{id}.pdf` (rendered PDF,
written by the doc-generator, never by this service).
**Testing**: JUnit 5, Mockito, `MockRestServiceServer`/WireMock (new stubs: `enforcement_area`,
`generate-document`), embedded Artemis (`DocGeneratorEventListener`), a `@Primary` fake Service Bus
sender/listener in integration tests (no embedded-broker equivalent exists for Azure Service Bus in this repo),
Azurite `GenericContainer` for the Blob integration test (`azurite-enabled=true` + test-only connection-string,
mirroring `cpp-context-system-doc-generator`'s own precedent), real local Postgres. **100% unit test coverage**
for every new class; **exactly 4 integration tests** (1 positive end-to-end + 3 failure-point: doc-generation
failure, email-send failure, email validation-rejection failure).
**Target Platform**: Existing CP AKS deployment, internal ingress only (unchanged — no new inbound surface).
**Project Type**: Single Spring Boot service (this repo) calling three external, independently-owned interfaces:
`enforcement_area` reference-data lookup (CIMD-4389, no contract document exists yet — open dependency),
`cpp-context-system-doc-generator`'s BYO-FS command/event pair (branch `team/CHD-2786`, unmerged), and
`cp-gov-uk-notify-gateway`'s command/event pair (Azure Service Bus). None of these three repos are changed by
this feature.
**Performance Goals**: Not quantified anywhere in spec.md (flagged in `checklists/pre-plan.md` CHK022) — no
delivery-time SLA stated. The only hard constraint is SC-004: no hearing capture is ever blocked, delayed, or
rejected by this pipeline, satisfied structurally by FR-010's independent task chain (async, non-blocking).
**Constraints**: FR-010 (independent task chain, no shared failure path with GOB); no defendant PII in logs
(FR-016 scopes exactly which fields are logged; constitution IV) — open dependency: whether `result_short_codes`
(offence wording + prompt values, at rest in Postgres) needs its own ADR, per constitution IV's "before the data
is stored" rule (not yet written — see Dependencies); no SAS/connection-strings/account-keys in any real
environment (constitution + CLAUDE.md hard rule; a test-only Azurite connection-string is the sole exception,
gated by `azurite-enabled`); idempotency via DB unique constraint (constitution III), not an app-only check;
manual-only recovery for FR-017 (Clarification 2026-11-24: automatic retry explicitly out of scope, deferred to
a separate story — compliant with constitution V, which permits but does not require automatic retry even where
the external system is confirmed idempotent); go-live blocked on `team/CHD-2786` merging to `main`.
**Scale/Scope**: Enforcement/Confiscation cases only; one `enforcement_result_notification` row per unique
`relatedReferenceNumber` per triggering share event, offences grouped within that row; reuses feature 003's
existing trigger endpoint verbatim (no new inbound endpoint, no Function App change).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* Evaluated against constitution
**v1.2.1**.

| Principle | Check | Status |
|---|---|---|
| I. Logic in the workflow, thin gateway | All eligibility (FR-001 trigger-shortCode gate, first-share only; FR-011 confirms no gate on reshare), mapping (Division Code derivation, per-offence grouping) and idempotency logic live in this workflow. This feature never touches `service-cp-crime-results-enforcementgateway` or the GOB/Libra path at all (FR-010 keeps the two pipelines fully separate) — Principle I's "thin gateway" concern doesn't apply here. | Pass |
| II. Compile-time contracts | None of the three external interfaces (`enforcement_area`, doc-generator, gov-uk-notify-gateway) are owned by this project, and none is the named `cpp-context-hearing-nows` exception. Applying that exception's spirit by analogy: this feature keeps verbatim copies of the gateway's three JSON schemas (`command-send-email-notification.schema.json`, `notification-sent.schema.json`, `notification-failed.schema.json`, already fetched and quoted in `research.md`/`contracts/`) marked "keep in sync; do not edit independently", validates built payloads against them in tests, and does the same for the doc-generator's `generate-document` schema once confirmed against `team/CHD-2786`'s current HEAD. `enforcement_area` has **no contract document at all yet** (CIMD-4389 owner hasn't published one) — open dependency D1. | Pass with dependency D1 (interpretation: hearing-nows-style exception applied by analogy, not literally named) |
| III. Persistence by convention | Flyway `V1.010`, one change per file. Idempotency enforced by `UNIQUE(trigger_notification_id, related_reference_number)` — a DB constraint, not only an application check (corrected from an earlier draft that would have used the wrong key — see `research.md`). | Pass |
| IV. Personal data | FR-016 explicitly scopes logged fields (caseUrn, Division Code, team email, shortCodes, sharing user id, timestamp) — no defendant name/DOB/NINO/address/bank data. `result_short_codes` at rest (offence wording, judicial result labels, prompt values) is new persisted content this constitution principle requires an ADR for **before** it is stored — none exists yet. | Pass with dependency D2 (ADR required before this table is first written in a real environment) |
| V. Bounded, resilient processing | Every outbound call (REST to `enforcement_area`/doc-generator, Blob, Service Bus) has explicit timeouts. A failure processing one `relatedReferenceNumber`'s row never blocks another (SC-005; FR-010's independent task chain). Every attempt ends in a recorded final state (the state machine's terminal states are all final). Automatic retry is explicitly not built (Clarification 2026-11-24) — permitted but not required by this principle even where the external system is confirmed idempotent (the gateway's send step is), so this is the conservative, compliant choice, not a gap. | Pass |
| VI. Immutable identifiers | `relatedReferenceNumber`/`caseUrn` are read, never reformatted or truncated. Division Code is a locally **derived** classification value (a prefix read from `relatedReferenceNumber`), not a modification of the identifier itself — the identifier passed to every external system (doc-generator, gateway, blob paths) is this row's own generated `id`, never a mangled reference number. | Pass |
| VII. Tested and clean | Unit tests first, 100% coverage of every new class; integration test per new flow (4 specified: 1 positive, 3 failure-point) — this feature adds no new endpoint, so CLAUDE.md's "IT per new endpoint" rule doesn't add a fifth. PMD must pass with no violations. | Pass |
| VIII. Security boundary | No new externally-reachable endpoint (reuses feature 003's existing internal trigger). New outbound-only integrations authenticate via Managed Identity/Workload Identity exclusively — no keys, no SAS. RBAC role assignment on the new Storage Account container and Service Bus namespace/queues for this workload's identity is a deployment-config dependency, not a code change — open dependency D3. | Pass with dependency D3 |
| IX. Iterative delivery, explicit assumptions | Every open point from this session is recorded with a safe interim and an owner in the Dependencies table below (Division Code prefix length, `enforcement_area` contract shape, `team/CHD-2786` go-live gate, the offence-vanishing edge case, ADR for data-at-rest). None blocks the design silently. | Pass |
| Architecture constraints | New `client`/`config`/`service`/`entity`/`repository` files follow the existing template package layout. Azure Blob/Service Bus SDKs are ordinary new dependencies, not changes to a `cpp-context-*` repository. `cp-task-manager`'s `jobs` table is already workflow-owned (feature 003's migrations) — no new DDL needed for the task-manager itself, only this feature's own new table. | Pass |
| Dev workflow | Branch `dev/cct1222-email-notification` does not follow the `005-…` naming convention — same situation feature 003 already hit and justified (see Complexity Tracking). `/speckit-analyze` must report no CRITICAL/HIGH findings before `/speckit-implement`, consistent with this file's Dependencies table. | Pass (justified) |

**Gate result**: passes, with twelve open dependencies (D1–D12) tracked below — none blocks `/speckit-tasks`,
each has a named owner and a safe interim per constitution IX. D9 (FR-008 amendment-reason source) was added
after `/speckit-analyze` flagged it as a coverage gap and should be resolved before `/speckit-implement` closes
out FR-008. D10 (Docmosis templates) is confirmed to be entirely `cpp-context-system-doc-generator`'s own scope,
not a gap in this feature's tasks — tracked here only as a release dependency alongside D4. Re-checked after
Phase 1 design (data-model.md, contracts/): no new violation introduced.

## Project Structure

### Documentation (this feature)

```text
specs/005-cimd-4548-4543-enforcement-result-notification/
├── spec.md
├── plan.md                                          # this file
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── gov-uk-notify-gateway-send-email.md           # + verbatim schema copies (Principle II)
│   ├── system-doc-generator-byo-fs.md                # + verbatim schema copy once confirmed against team/CHD-2786 HEAD
│   └── enforcement-area.md                           # new; documents the assumed shape pending CIMD-4389 (D1)
├── checklists/
│   ├── requirements.md
│   └── pre-plan.md
└── tasks.md                                          # /speckit-tasks (not created by /speckit-plan)
```

### Source Code (repository root)

```text
src/main/java/uk/gov/hmcts/cp/
├── entity/   EnforcementResultNotificationEntity, ResultNotificationStatus, NotificationType, Area
├── repository/ EnforcementResultNotificationRepository
├── service/  ResultNotificationStore, ResultNotificationTasks, ResultNotificationRetries,
│             ResolveResultNotificationTask, MatchResultNotificationTask, DispatchResultNotificationEmailTask,
│             DocGeneratorEventListener, ResultNotificationReplyListener, ResultNotificationPayloadBuilder,
│             TriggerShortCodeMatcher, DivisionCode
├── client/   EnforcementAreaClient, TeamInbox, EnforcementAreaUnavailableException,
│             DocGeneratorClient, GenerateDocumentCommand, DocGenerationUnavailableException,
│             ResultNotificationBlobClient, GovUkNotifyEmailSender, GovUkNotifyUnavailableException
├── config/   ResultNotificationBlobProperties, ResultNotificationBlobConfig, GovUkNotifyProperties,
│             GovUkNotifyServiceBusConfig, ResultNotificationProperties (+ extend ClientProperties, RestClientConfig)
└── event/    HearingResultedEvent  # changed: Offence gains offenceTitle/offenceDetails; JudicialResult gains resultLabel (additive)

src/main/resources/db/migration/V1.010__create_enforcement_result_notification.sql
src/main/resources/application.yaml                   # new cp.* sections

src/test/java/uk/gov/hmcts/cp/ (unit tests per new class, 100% coverage target)
src/test/resources/contracts/gov-uk-notify-gateway/ (verbatim schema copies, Principle II)
wiremock/mappings/ (new: enforcement-area, generate-document stubs — repo-root wiremock/, not src/test/resources/)

build.gradle   # + com.azure:azure-storage-blob, com.azure:azure-messaging-servicebus (pinned versions)
```

**Structure Decision**: Single project, existing template package layout unchanged (no new top-level package
introduced beyond what the template already defines); this feature only adds files within `client`, `config`,
`entity`, `event`, `repository`, `service`.

## Dependencies and sequencing

| # | Dependency | Needed for | Owner |
|---|---|---|---|
| D1 | `enforcement_area` REST contract (path, request/response shape) published for CIMD-4389 | `EnforcementAreaClient` finalisation | Reference-data team |
| D2 | ADR recording whether `result_short_codes` (offence wording + prompt values) at rest requires encryption/retention treatment, per constitution IV | First real-environment write to `enforcement_result_notification` | Architect / IA, before go-live |
| D3 | Workload Identity RBAC role assignment on the new Blob container and the `ng-send-email`/`enforcementworkflow-notification-result` Service Bus queues | Deployment to any real environment | Developer / platform |
| D4 | `team/CHD-2786` (`cpp-context-system-doc-generator`) merged to `main` and deployed | PDF rendering go-live (spec.md Assumptions) | System Doc Generator team |
| D5 | Division Code prefix length confirmed against CIMD-4172's field definition (assumed 3 characters) | `DivisionCode.from(relatedReferenceNumber)` finalisation | Developer, narrow confirmation |
| D6 | Confirm `ResultCodeResolver` (or whatever produces `ResolvedResult`/`ResolvedCodes` for the GOB pipeline today) is a standalone, independently-callable component | `MatchResultNotificationTask`'s reuse of it | Developer, before coding |
| D7 | Business confirmation of behaviour when an offence present in the previous share's snapshot is absent from the current state (reverted/removed) — this plan defaults to `current: null, changed: true` | `MatchResultNotificationTask`'s diff logic | Developer / business, not a blocker on design |
| D8 | Exact Artemis subscription/selector change needed to also receive `public.systemdocgenerator.events.document-available`/`generation-failed` alongside this service's existing `public.event` consumption | `DocGeneratorEventListener` wiring | Developer, before coding |
| D9 | Source for FR-008's "amendment reason" — no field for this exists anywhere in `HearingResultedEvent`, the Results API wire payload, or any reference-data client found in this codebase's research; "the original date of sentence" is satisfiable from the previous snapshot's `shortCodes[].orderedDate` and "the amendment date" from this row's own `received_at`, both already in the payload, but "amendment reason" has no identified source at all | FR-008 cannot be fully satisfied as literally worded without this | Business / Results API team — interim: render the PDF without an amendment-reason line until a source is confirmed |
| D10 | The two Docmosis templates (`notification_type` = FIRST_SHARE/AMEND_RESHARE) that `templateIdentifier` points at are authored and owned entirely within `cpp-context-system-doc-generator` (confirmed) — **not this repo's scope**, hence no task for them in this repo's `tasks.md`; this repo only configures the template ids (T018) and passes them through `generate-document` | FR-004/FR-008/SC-003 end-to-end verification against a real (non-stubbed) doc generator; WireMock stubs cover this repo's own tests without needing the real templates | System Doc Generator team — track as a release dependency (alongside D4) for go-live, not a gap in this feature's own tasks |
| D11 | Confirm FR-014's Welsh-language rendering actually extends to this feature's new `cp-gov-uk-notify-gateway`/doc-generator delivery mechanism — spec.md's Assumptions claim this "reuses conventions already established for other NOWS documents," but that convention was built for the legacy Office365/NOWS pathway, not this brand-new ASB-based integration, and no task currently tests it. **FR-013's >2MB Office365 fallback is not a practical risk for this feature** (confirmed): this PDF is a short per-case result summary (one row's matched offences/shortCodes/prompts), not a full case bundle, so it will not realistically approach 2MB — no dedicated test is needed for that path | FR-014 compliance | Developer, confirm Welsh rendering with the gateway/doc-generator teams before relying on inherited behaviour |
| D12 | **Traceability**: the Session 2026-11-24 clarification reversed FR-011/FR-012 to gate the amend-reshare path by the same trigger-shortCode list as first-share, reasoning only from an internal consistency concern (Acceptance Scenario 3's "no previous share to diff against" case) — without checking the real Jira ACs, which had not yet been fetched. That reversal is now confirmed wrong: CIMD-4543's actual acceptance criteria (fetched directly 2026-11-26) state explicitly that no shortCode gate applies to the A&R path (AC4/AC5/AC7). The original "no filter on reshare" design was correct all along; the concern that motivated the reversal was already independently handled by `ResultNotificationPayloadBuilder`'s existing `previous: null, changed: true` handling for a reshare with no prior SENT row. Corrected via Clarification 2026-11-26 across spec.md/plan.md/data-model.md/tasks.md | Avoiding repeat of this error; a reminder to ground clarifications in source tickets, not just internal consistency | Developer — closed, no further action needed beyond the correction itself |

Suggested iteration order (constitution IX): (1) data model + `ResolveResultNotificationTask`/
`MatchResultNotificationTask` against WireMock stubs (positive path, FIRST_SHARE) → (2) `DocGeneratorEventListener`
+ `DispatchResultNotificationEmailTask` + `ResultNotificationReplyListener` (full positive-path integration test)
→ (3) the three failure-point integration tests → (4) AMEND_RESHARE path (no shortCode gate, case-type
eligibility only — corrected 2026-11-26) and per-offence diff → (5) Blob/Service Bus production wiring once
D1/D3/D4 land.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Developed on branch `dev/cct1222-email-notification`, not a `005-…`-named feature branch (constitution: feature branch per spec) | Same situation feature 003 already hit and justified: branch creation/management is the developer's own process, not gated by this naming convention here | A rename now would disrupt in-progress work for a purely cosmetic naming fix |
| One duplicated `HearingResultsSource.sharedResults(...)` call per share (same external Results cache/API lookup the GOB pipeline already makes) | FR-010 requires this feature's task chain to be fully independent of the GOB pipeline's — sharing the already-fetched result would couple the two chains' failure paths | Sharing the fetched state across chains would violate FR-010's literal independence requirement the first time either chain needed to retry or replay without the other |
