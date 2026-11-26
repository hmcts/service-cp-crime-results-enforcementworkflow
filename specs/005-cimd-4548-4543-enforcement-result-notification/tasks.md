# Tasks: Enforcement Result Notification Email (CIMD-4548, CIMD-4543)

**Input**: Design documents from `specs/005-cimd-4548-4543-enforcement-result-notification/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md, checklists/

**Tests**: MANDATORY in this project (constitution Principle VII) — 100% unit test coverage of every new class
(plan.md "Testing"), plus exactly 5 integration tests (1 positive end-to-end + 4 failure-point, including FR-020's
Case Material path), per research.md
R12. Every test task is written, and must fail, before its implementation task.

**Organization**: Grouped by user story (US1 = CIMD-4548 first-share, US2 = CIMD-4543 amend-reshare, US3 = FR-020
Case Material attachment). All three are P1. The foundational phase delivers the **full pipeline for the
current-state (FIRST_SHARE) shape**, including the FR-001/FR-011 trigger-shortCode gate; US2 then **extends** the
same `MatchResultNotificationTask`/`ResultNotificationPayloadBuilder` files with the Previous Share Snapshot
lookup and the current+previous diff, explicitly **skipping** that gate for AMEND_RESHARE (corrected 2026-11-26 —
see plan.md D12) — an additive, same-file extension, not a new component, so US1 remains independently completable
and testable on its own first. US3 is a **wholly new, independent branch** off the same `DOC_READY` state (new
client/task/entity columns, no changes to US1/US2's files beyond `DocGeneratorEventListener`'s fan-out), so it can
be built in parallel with US2 once Foundational is done.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no unmet dependencies)
- **[Story]**: US1 or US2 — Setup/Foundational/Polish tasks carry no story label
- File paths are exact; package root is `src/main/java/uk/gov/hmcts/cp/`, test root `src/test/java/uk/gov/hmcts/cp/`

---

## Phase 1: Setup

- [X] T001 [P] Add `com.azure:azure-storage-blob` and `com.azure:azure-messaging-servicebus` to `build.gradle`
      with explicit pinned versions (not a BOM), matching how `com.azure:azure-identity:1.18.1` is already pinned
- [X] T002 [P] Add new WireMock stub mappings `wiremock/mappings/enforcement-area-*.json` (200 match + 404
      no-match, per `contracts/enforcement-area.md`'s assumed shape) and
      `wiremock/mappings/generate-document-*.json` (per `contracts/system-doc-generator-byo-fs.md`), alongside the
      existing `wiremock/mappings/reference-data-result-definition.json` style
- [X] T003 [P] Copy the three verbatim JSON schemas from `hmcts/cp-gov-uk-notify-gateway`
      (`command-send-email-notification.schema.json`, `notification-sent.schema.json`,
      `notification-failed.schema.json`) into `src/test/resources/contracts/gov-uk-notify-gateway/`, each marked
      "keep in sync; do not edit independently" (constitution Principle II, plan.md Constitution Check)

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T004 Create Flyway migration `src/main/resources/db/migration/V1.011__create_enforcement_result_notification.sql`
      — the `enforcement_result_notification` table, `uq_ern_trigger_ref` unique index
      `(trigger_notification_id, related_reference_number)`, and `idx_ern_hearing_ref_prev`
      `(hearing_id, related_reference_number, received_at DESC) WHERE status='SENT'` — exact columns per
      data-model.md
- [X] T005 [P] Create `entity/NotificationType.java`, `entity/ResultNotificationStatus.java` (the state machine's
      terminal/non-terminal states per data-model.md, including `SUPPRESSED` reachable from **both** notification
      types), `entity/Area.java` enums
- [X] T006 Create `entity/EnforcementResultNotificationEntity.java` mapping every column from data-model.md,
      `@PrePersist`/`@PreUpdate` for `received_at`/`updated_at` (mirrors `EnforcementNotificationEntity`'s
      convention) (depends on T004, T005)
- [X] T007 [P] Create `repository/EnforcementResultNotificationRepository.java` — basic CRUD + a stub method
      signature for the Previous Share Snapshot query (implemented in US2, T052) (depends on T006)
- [X] T008 [P] Unit test `service/DivisionCodeTest.java` — prefix extraction from `relatedReferenceNumber`,
      integer parsing (not string comparison, since the Confiscation set mixes 2/3-digit values), the worked
      example `"0100104567C"` → `10` → Enforcement
- [X] T009 Implement `service/DivisionCode.java` (depends on T008; confirm plan.md D5 — exact prefix length,
      assumed 3 chars — against CIMD-4172 before finalising)
- [X] T010 [P] Unit test `client/EnforcementAreaClientTest.java` using `MockRestServiceServer` — 404 → empty,
      other HTTP error/timeout → `EnforcementAreaUnavailableException` (never collapsed into a confirmed no-match)
- [X] T011 Implement `client/EnforcementAreaClient.java` + `client/TeamInbox.java` +
      `client/EnforcementAreaUnavailableException.java`, mirroring `ReferenceDataClient`'s existing shape (depends
      on T010; contract per `contracts/enforcement-area.md` — flagged assumption, plan.md D1)
- [X] T012 [P] Unit test `client/DocGeneratorClientTest.java` — `generate-document` command construction
      (`templateIdentifier`, `conversionFormat=pdf`, `requestedTime`, `payloadFileUri`, `destinationFileUri`,
      `originatingSource`, `sourceCorrelationId`); no query/poll method
- [X] T013 Implement `client/DocGeneratorClient.java` + `client/GenerateDocumentCommand.java` +
      `client/DocGenerationUnavailableException.java` (depends on T012; contract per
      `contracts/system-doc-generator-byo-fs.md`)
- [X] T014 [P] Unit test `client/ResultNotificationBlobClientTest.java` — payload upload via
      `BlobParallelUploadOptions` with metadata/tags (`notificationId`, `notificationType`), destination URL
      computed (never uploaded to), delete-if-exists for both blob names on purge, single container with
      `result-notification/requests/{id}` / `result-notification/documents/{id}.pdf` path prefixes
- [X] T015 Implement `client/ResultNotificationBlobClient.java` + `config/ResultNotificationBlobProperties.java`
      + `config/ResultNotificationBlobConfig.java` — one `BlobContainerClient` produced once at startup
      (`createIfNotExists()`), `DefaultAzureCredentialBuilder()` unconditionally for every real environment,
      `azurite-enabled` (default `false`) switching to `connection-string`/SharedKey for local dev/IT only
      (depends on T014; research.md R8)
- [X] T016 [P] Unit test `client/GovUkNotifyEmailSenderTest.java` — `ServiceBusMessage` construction:
      `subject="send-email-notification"`, `ReplyTo=enforcementworkflow-notification-result`, `correlationId` in
      application properties, body `{notificationId, templateId, sendToAddress, fileUri}`
- [X] T017 Implement `client/GovUkNotifyEmailSender.java` + `client/GovUkNotifyUnavailableException.java` +
      `config/GovUkNotifyProperties.java` + `config/GovUkNotifyServiceBusConfig.java` (depends on T016)
- [X] T018 [P] Create `config/ResultNotificationProperties.java` (trigger shortCode lists — 16 Enforcement +
      Confiscation NOWS-matrix codes, `confiscation-division-codes: [45,66,97,109,110,116]`, Docmosis template
      ids per `notification_type`, this feature's own retry/backoff schedule) and add the new `cp.*` sections to
      `src/main/resources/application.yaml` (`cp.enforcement-area.*`, `cp.doc-generator.*`,
      `cp.result-notification-blob.*`, `cp.gov-uk-notify.*`, `cp.result-notification.*` per plan.md's Config
      section)
- [X] T019 [P] Unit test `service/TriggerShortCodeMatcherTest.java` — the FR-001 gate: does the hearing's
      current state include at least one shortCode from the trigger list for that case's area; this matcher is
      invoked for FIRST_SHARE only — AMEND_RESHARE never calls it (corrected 2026-11-26, plan.md D12)
- [X] T020 Implement `service/TriggerShortCodeMatcher.java` (depends on T019, T018)
- [X] T021 [P] Unit test `service/ResultNotificationStoreTest.java` (`REQUIRES_NEW`-per-write pattern, mirrors
      `NotificationStore`), `service/ResultNotificationTasksTest.java` (job-queueing), and
      `service/ResultNotificationRetriesTest.java` (retry/backoff scheduling)
- [X] T022 Implement `service/ResultNotificationStore.java` + `service/ResultNotificationTasks.java` +
      `service/ResultNotificationRetries.java` (depends on T021, T006, T007)
- [X] T023 Add `resultNotificationTasks.queueResolve(entity.getId());` to `NotificationService.store()` in
      `src/main/java/uk/gov/hmcts/cp/service/NotificationService.java`, inside the existing transaction next to
      `tasks.queueProcess(entity.getId())` (depends on T022; research.md R4 — the independent task chain decision)
- [X] T024 [P] Unit test `event/HearingResultedEventTest.java` additions — `Offence.offenceTitle`/`offenceDetails`
      (← wire `offenceTitle`/`wording`) and `JudicialResult.resultLabel` (← wire `resultText`) deserialize
      correctly from the existing `wiremock/mappings/results-hearing-details.json` fixture
- [X] T025 Extend `event/HearingResultedEvent.java` additively — `Offence` gains `offenceTitle`/`offenceDetails`,
      `JudicialResult` gains `resultLabel` (depends on T024; research.md R7a — shared with the GOB pipeline,
      purely additive, no existing field renamed or removed)
- [X] T026 [P] Unit test `service/ResolveResultNotificationTaskTest.java` — reuses
      `HearingResultsSource.sharedResults(...)` and the existing `EnforcementCaseSelector.select(event)` as-is;
      creates one `REQUESTED` row per unique `relatedReferenceNumber` (idempotent on `uq_ern_trigger_ref`); sets
      `notification_type` from the trigger notification's `isReshare` flag
- [X] T027 Implement `service/ResolveResultNotificationTask.java` (depends on T026, T022, T025)
- [X] T028 [P] Unit test `service/ResultNotificationPayloadBuilderTest.java` — groups `ResolvedResult` by
      `offenceId` into the per-offence `result_short_codes` schema (data-model.md), current-state only (no
      previous/changed fields yet — added in US2, T053)
- [X] T029 Implement `service/ResultNotificationPayloadBuilder.java`, current-state (FIRST_SHARE) shape only
      (depends on T028, T025; confirm plan.md D6 — `ResultCodeResolver`/equivalent is independently callable,
      before coding this reuse)
- [X] T030 [P] Unit test `service/MatchResultNotificationTaskTest.java` — the shared pipeline (FIRST_SHARE shape):
      FR-001 gate via `TriggerShortCodeMatcher` → Division Code resolution → `EnforcementAreaClient` lookup →
      `INBOX_NOT_FOUND` on confirmed no-match only (not on transient 5xx/timeout) → build current-state payload →
      upload to blob → send `generate-document` → row → `DOC_REQUESTED`
- [X] T031 Implement `service/MatchResultNotificationTask.java`, current-state path only (depends on T030, T009,
      T011, T013, T015, T020, T029)
- [X] T032 [P] Unit test `service/DocGeneratorEventListenerTest.java` — matches `sourceCorrelationId == row.id`
      and `originatingSource == ours`, ignores anything else; `document-available` → `DOC_READY` + queues email
      task; `generation-failed` → `DOC_FAILED` (final)
- [X] T033 Implement `service/DocGeneratorEventListener.java` (depends on T032, T022; confirm plan.md D8 — the
      exact Artemis subscription/selector change needed alongside this service's existing `public.event`
      consumption)
- [X] T034 [P] Unit test `service/DispatchResultNotificationEmailTaskTest.java` — builds the
      `send-email-notification` command (`notificationId`=row `id`, `templateId` per `notification_type`,
      `sendToAddress`=`team_email`, `fileUri`=`destination_file_uri`); row → `EMAIL_REQUESTED`
- [X] T035 Implement `service/DispatchResultNotificationEmailTask.java` (depends on T034, T017)
- [X] T036 [P] Unit test `service/ResultNotificationReplyListenerTest.java` — `notification-sent` (bare ASB
      `subject`, **not** `public.notificationnotify.events.*` — research.md R1 correction) → `SENT` + FR-016 log
      line + purge both blobs + `purged_at` (never before this confirmation); `notification-failed` →
      `EMAIL_FAILED`; `notification-validation-failed` → `EMAIL_FAILED`, correlating via the rejected command's
      echoed `originalEventPayload`
- [X] T037 Implement `service/ResultNotificationReplyListener.java` (depends on T036, T015 for blob delete, T017)

**Checkpoint**: The full pipeline skeleton exists for the current-state (FIRST_SHARE-shaped) flow. US1 adds its
own tests/scenarios on top of this; US2 extends `MatchResultNotificationTask`/`ResultNotificationPayloadBuilder`
with the previous-share diff.

---

## Phase 3: User Story 1 - First-share notification to the responsible team (Priority: P1) 🎯 MVP

**Goal**: Deliver CIMD-4548 end to end — a hearing resulted and shared for the first time with at least one
trigger shortCode produces exactly one notification email to the correct team inbox.

**Independent Test** (spec.md User Story 1): Share a hearing result on an Enforcement case where a captured
result shortCode is in the trigger list; verify exactly one email with the correct PDF is delivered to the
correct team inbox, logged, and that re-submitting the same share produces no second email.

### Tests for User Story 1

- [X] T038 [P] [US1] Integration test `service/ResultNotificationFlowIntegrationTest.java` (extends
      `EmbeddedBrokerIntegrationTestBase`/`WorkflowStubsIntegrationTestBase`) — the **positive path**:
      `REQUESTED → DOC_REQUESTED` → publish `document-available` on the embedded broker → `DOC_READY` → publish
      the fake-ASB-sender's `notification-sent` → `SENT`; asserts both blobs purged after `SENT` and the FR-016
      log fields via `OutputCaptureExtension`
- [X] T039 [P] [US1] Integration test (same class, new method) — **doc-generation failure**: publish
      `generation-failed` instead; assert `DOC_REQUESTED → DOC_FAILED`, `last_error` populated, no email ever
      attempted, blobs **not** purged
- [X] T040 [P] [US1] Integration test (same class, new method) — **email-send failure (post-acceptance)**:
      publish `notification-failed`; assert `EMAIL_REQUESTED → EMAIL_FAILED`, `last_error` populated, blobs
      **not** purged
- [X] T041 [P] [US1] Integration test (same class, new method) — **email validation-rejection failure**: publish
      `notification-validation-failed` with an echoed `originalEventPayload`; assert
      `EMAIL_REQUESTED → EMAIL_FAILED`, `reason`/`details` logged for triage
- [X] T042 [P] [US1] Unit test (extend `MatchResultNotificationTaskTest.java`) — `SUPPRESSED` when FIRST_SHARE's
      current state has no trigger shortCode present (FR-005)
- [X] T043 [P] [US1] Unit test (extend `MatchResultNotificationTaskTest.java`) — re-submitting an unmodified
      first share produces no second email, idempotent on `uq_ern_trigger_ref` (FR-005, FR-015)

### Implementation for User Story 1

- [X] T044 [US1] Confirm FR-016's exact logged fields (caseUrn, resolved Division Code, recipient team email,
      triggering shortCodes, sharing user id, ISO-8601 UTC timestamp) are emitted on every terminal outcome
      (`SENT`, `SUPPRESSED`, `INBOX_NOT_FOUND`, `DOC_FAILED`, `EMAIL_FAILED`) — add any missing log statement in
      the relevant task/listener class (T027, T031, T033, T035, T037); for `SUPPRESSED` specifically, log the
      full current-state shortCode set (not an empty array), per FR-016's SUPPRESSED clause
- [X] T045 [US1] Verify no defendant PII (name/DOB/NINO/address/bank detail) reaches any log line this feature
      adds, per constitution Principle IV — add the log-assertion test this principle requires for each new flow

**Checkpoint**: User Story 1 (CIMD-4548) is fully functional and independently testable/deployable as the MVP.

---

## Phase 4: User Story 2 - Amend-and-reshare notification with before/after comparison (Priority: P1)

**Goal**: Deliver CIMD-4543 — every amend-and-reshare event on a previously-shared, in-scope Enforcement/
Confiscation case produces an email showing the original result alongside the new one, diffed against the
immediately preceding share, regardless of which shortCode(s) are present/added/removed/changed, and
independent of the (unrelated) GOB payload submission's own success or failure.

**Independent Test** (spec.md User Story 2): Share a hearing result once, then amend and reshare it; verify
exactly one amend-reshare email is sent with a PDF correctly comparing the original and new results, regardless
of which shortCode(s) changed — including a reshare that only ever touched non-trigger-list shortCodes
(CIMD-4543 AC4/AC5/AC7; corrected 2026-11-26, plan.md D12: there is no shortCode gate on this path).

### Tests for User Story 2

- [X] T046 [P] [US2] Unit test (extend `EnforcementResultNotificationRepositoryTest` or equivalent) — the
      Previous Share Snapshot query (`idx_ern_hearing_ref_prev`): returns the single most recent `SENT` row for
      `(hearing_id, related_reference_number)`, `LIMIT 1`, and `Optional.empty()` when none exists
- [X] T047 [P] [US2] Unit test (extend `ResultNotificationPayloadBuilderTest.java`) — the AMEND_RESHARE payload
      shape: per-offence `current`+`previous`+`changed` merge; an offence with no match in the previous snapshot
      → `previous: null, changed: true`; top-level `amendmentDate` populated from the row's `received_at`
      (plan.md D9 — `amendmentReason` has no source and is intentionally absent from the payload)
- [ ] T048 [P] [US2] Unit test (extend `MatchResultNotificationTaskTest.java`) — reopened/rewritten 2026-11-26
      (plan.md D12): the AMEND_RESHARE branch never calls `TriggerShortCodeMatcher` and never reaches
      `SUPPRESSED`. Three sub-cases, all starting from "first share suppressed, no trigger shortCode, no email
      ever sent" (CIMD-4543 AC4/AC5/AC7): amending to **add** a trigger shortCode → proceeds; amending to add a
      non-trigger shortCode → proceeds; changing an existing non-trigger value (no trigger shortCode involved at
      all) → still proceeds. All three produce an email.
- [ ] T049 [P] [US2] Integration test (extend `ResultNotificationFlowIntegrationTest.java`) — AMEND_RESHARE
      positive path: the "previous" column renders from the prior `SENT` row's `result_short_codes`, **not** the
      Results cache/API (research.md R3, since by the time an amend-reshare is processed the cache/API already
      reflect the amended state)
- [ ] T050 [P] [US2] Unit test — three successive amend-reshares (spec.md User Story 2 Acceptance Scenario 6)
      each diff against their own immediately-preceding `SENT` row, not the original first share (FR-009)
- [ ] T051 [P] [US2] Unit test — reworded 2026-11-26 (plan.md D12): a reshare with no observable change from the
      previous share still produces an email; no content-diff suppression (FR-012) — this path has no
      shortCode gate to pass in the first place, so the assertion is purely about content-diff, not gate-passing

### Implementation for User Story 2

- [X] T052 [US2] Add the Previous Share Snapshot query method to
      `repository/EnforcementResultNotificationRepository.java` (depends on T046, T007)
- [X] T053 [US2] Extend `service/ResultNotificationPayloadBuilder.java` with the AMEND_RESHARE current+previous+
      changed merge, plus the top-level `amendmentDate` field (sourced from this row's own `received_at` —
      plan.md D9) (depends on T047, T029)
- [ ] T054 [US2] Rewritten 2026-11-26 (plan.md D12): extend `service/MatchResultNotificationTask.java`'s
      AMEND_RESHARE branch to **skip** `TriggerShortCodeMatcher` entirely — AMEND_RESHARE never gates on
      shortCodes and never reaches `SUPPRESSED` (data-model.md state machine reverted to FIRST_SHARE-only
      `SUPPRESSED`) — and always proceed straight to the Previous Share Snapshot lookup and inbox resolution
      (depends on T048, T052, T053, T031)
- [X] T055 [US2] Confirm FR-010 independence end to end: an amend-reshare email is still dispatched when the
      (unrelated) GOB payload submission fails independently, with no shared failure path with
      `ProcessNotificationTask`/`PublishGobDataTask` (spec.md User Story 2 Acceptance Scenario 5) — add this as an
      explicit integration or unit test assertion, not just an architectural property

**Checkpoint**: User Stories 1 AND 2 (CIMD-4548 and CIMD-4543) both work independently and together.

---

## Phase 5: User Story 3 - Case material attachment (CIMD-4548/4543 FR-020) (Priority: P1)

**Goal**: Attach the rendered notification PDF to the case's Case Material section in CP (as "Electronic
Notifications") for both first-share and amend-reshare, independently of email dispatch (FR-010/FR-020) — a
coverage gap against the real Jira ACs found after initial implementation, now unblocked: `documentTypeId`
(`f471eb51-614c-4447-bd8d-28f9c2815c9e`), system-user auth, and the `prosecutionCaseId` identity match are all
confirmed (spec.md Clarifications, Session 2026-11-26).

**Independent Test**: Share (or amend-reshare) a hearing result that reaches `DOC_READY`; verify the PDF is
attached to the case's Case Material section under "Electronic Notifications" even when email dispatch is
deliberately made to fail, and vice versa — the two outcomes never block or depend on each other.

### Tests for User Story 3

- [ ] T056 [P] [US3] Unit test `client/ProgressionClientTest.java` using `MockRestServiceServer` — Call 1
      (multipart `upload-court-document` at `POST /courtdocument/{materialId}`, CJSCPPUID header); Call 2
      (`application/vnd.progression.add-court-document+json`, same URL, body `{courtDocument: {...,
      documentTypeId: "f471eb51-614c-4447-bd8d-28f9c2815c9e", materials: [{id: materialId}]}}`); non-2xx/timeout
      on either call → `ProgressionUnavailableException` (transient, must be retried, never collapsed into a
      terminal failure)
- [ ] T057 [P] [US3] Unit test `service/UploadCaseMaterialTaskTest.java` — reads the PDF back out of
      `ResultNotificationBlobClient` via `destination_file_uri`; generates `material_id` once and reuses it on
      retry (never re-generates); `NOT_STARTED → UPLOADING → UPLOADED → ATTACHED`; a Progression failure at either
      call leaves `case_material_status` at `UPLOAD_FAILED`/`ATTACH_FAILED` without touching the row's email
      `status`
- [ ] T058 [P] [US3] Integration test (extend `ResultNotificationFlowIntegrationTest.java`) — the existing
      positive path additionally asserts `case_material_status` reaches `ATTACHED` alongside `SENT` on the same
      row
- [ ] T059 [P] [US3] Integration test (same class, new method) — **Case Material failure-point**: stub
      `progression-court-document` to fail; assert `case_material_status` reaches `ATTACH_FAILED` (or
      `UPLOAD_FAILED`) while the row's email `status` still independently reaches `SENT` on the same share (FR-010
      extended to FR-020)

### Implementation for User Story 3

- [ ] T060 [US3] Create Flyway migration
      `src/main/resources/db/migration/V1.012__add_case_material_to_enforcement_result_notification.sql` —
      `material_id` (UUID NULL), `case_material_status` (VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED'), and
      `prosecution_case_id` (UUID NULL) on `enforcement_result_notification`
- [ ] T061 [P] [US3] Create `entity/CaseMaterialStatus.java` (`NOT_STARTED`, `UPLOADING`, `UPLOADED`, `ATTACHED`,
      `UPLOAD_FAILED`, `ATTACH_FAILED`) and add `material_id`/`case_material_status`/`prosecution_case_id` columns
      to `entity/EnforcementResultNotificationEntity.java` (depends on T060)
- [ ] T062 [US3] Amend `service/ResultNotificationStore.createRequested(...)` and
      `service/ResolveResultNotificationTask.createRowIfSelected(...)` (both previously completed, T022/T027) to
      additionally persist `prosecution_case_id` = `prosecutionCase.get().id()` at row-creation time, alongside the
      already-persisted `caseUrn` — this value was available there all along but not previously stored, since
      nothing needed it before FR-020 (depends on T061)
- [ ] T063 [P] [US3] Add `cp.progression.*` properties (base-url, cjscppuid, timeouts) to `ClientProperties` and a
      `progressionRestClientBuilder` bean to `RestClientConfig`, plus the new `cp.progression.*` section in
      `application.yaml`
- [ ] T064 [US3] Implement `client/ProgressionClient.java` + `client/ProgressionUnavailableException.java`
      (depends on T056, T063; contract per `contracts/progression-court-document.md`)
- [ ] T065 [US3] Add a case-material status update method to `service/ResultNotificationStore.java` (mirrors the
      existing `markDocFailed`/`markSent`-style methods, independent of the email-path `status` column) (depends
      on T061)
- [ ] T066 [US3] Implement `service/UploadCaseMaterialTask.java`, queued from `DocGeneratorEventListener`'s
      `document-available` handler alongside (not instead of) `DispatchResultNotificationEmailTask`, reusing
      `ResultNotificationRetries`'s shape for `UPLOAD_FAILED`/`ATTACH_FAILED` (depends on T057, T062, T064, T065)
- [ ] T067 [US3] Wire `UploadCaseMaterialTask` into `service/DocGeneratorEventListener.java`'s `document-available`
      branch (depends on T066) — both new and existing branches must queue independently per FR-010/FR-020
- [ ] T068 [US3] Add new WireMock stub mappings `wiremock/mappings/progression-court-document-*.json` (both
      Call 1 and Call 2, success + failure) and write `contracts/progression-court-document.md` documenting the
      confirmed two-call sequence (data-model.md already has the detail; this file is the contract-doc copy per
      Principle II convention)

**Checkpoint**: User Stories 1, 2, AND 3 (CIMD-4548, CIMD-4543, FR-020) all work independently and together.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T069 [P] Run `quickstart.md`'s full validation sequence (all smoke tests, including Case Material
      attachment) against a local stack
- [X] T070 [P] Confirm 100% unit test coverage across every new class listed in plan.md's Source Code tree
      (constitution Principle VII) — close any gap found
- [ ] T071 [P] `./gradlew build` including PMD (`pmdMain`, `pmdTest`) clean, no suppressions without an inline
      justification
- [ ] T072 Re-verify plan.md's D1 (`enforcement_area` contract), D4 (`team/CHD-2786` current HEAD), D8 (Artemis
      selector), and D13 (Progression system-user registration) against their real-world state immediately before
      this feature's go-live
- [ ] T073 Write the ADR required by constitution Principle IV / plan.md D2 (data-at-rest treatment for
      `result_short_codes`) before this table is first written in any real environment
- [ ] T074 Confirm Workload Identity RBAC (plan.md D3) is granted on the new Blob container and the
      `ng-send-email`/`enforcementworkflow-notification-result` Service Bus queues before deployment to any real
      environment
- [ ] T075 `mvn clean && ./runIntegrationTests.sh` (or this repo's documented IT command) green, per CLAUDE.md's
      hard rule

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — can start immediately.
- **Foundational (Phase 2)**: depends on Setup; **blocks all three user stories**. T004→T006→T007; clients (T008–T017)
  are independent of each other and of the entity chain, so can run in parallel once Setup is done; T018–T037
  build up the task/listener chain in the dependency order listed (each `[P]` test task before its own
  implementation task).
- **User Story 1 (Phase 3)**: depends on Foundational. Fully independent of US2.
- **User Story 2 (Phase 4)**: depends on Foundational. **Extends** two files US1 already exercises
  (`MatchResultNotificationTask`, `ResultNotificationPayloadBuilder`) rather than creating new components —
  sequence US2's implementation tasks (T052–T055) after US1 is complete to avoid merge churn on the same files,
  even though nothing in US2's design *requires* US1 to be functionally complete first.
- **User Story 3 (Phase 5)**: depends on Foundational only (specifically `DocGeneratorEventListener`, T033) — not
  on US1 or US2 being complete, since it is a wholly new branch off `DOC_READY` touching no file either of them
  own except `DocGeneratorEventListener`'s fan-out (T067). Can run fully in parallel with US2.
- **Polish (Phase 6)**: depends on all three user stories.

### Parallel Opportunities

- T001–T003 (Setup) in parallel.
- Within Foundational: T005, T008/T010/T012/T014/T016/T018/T019/T021/T024/T026/T028/T030/T032/T034/T036 (every
  test task) can run in parallel with each other and with unrelated implementation tasks, as long as each
  implementation task still waits for its own paired test task.
- Within US1: T038–T043 (all test tasks) in parallel.
- Within US2: T046–T051 (all test tasks) in parallel.
- Within US3: T056–T059 (all test tasks) in parallel; US3 as a whole can run in parallel with US2.
- T069–T071 (Polish) in parallel.

---

## Parallel Example: Foundational clients

```bash
Task: "Unit test client/EnforcementAreaClientTest.java"
Task: "Unit test client/DocGeneratorClientTest.java"
Task: "Unit test client/ResultNotificationBlobClientTest.java"
Task: "Unit test client/GovUkNotifyEmailSenderTest.java"
# all four are independent files with no cross-dependencies — run together, then their four
# implementation tasks (T011, T013, T015, T017) can also run together once their tests exist.
```

## Parallel Example: User Story 2 tests

```bash
Task: "Unit test: Previous Share Snapshot repository query"
Task: "Unit test: AMEND_RESHARE payload builder current+previous+changed merge"
Task: "Unit test: MatchResultNotificationTask's AMEND_RESHARE branch always proceeds (no shortCode gate)"
Task: "Integration test: AMEND_RESHARE positive path reads the prior SENT row, not the Results cache/API"
```

## Parallel Example: User Story 3 tests

```bash
Task: "Unit test client/ProgressionClientTest.java"
Task: "Unit test service/UploadCaseMaterialTaskTest.java"
Task: "Integration test: Case Material positive path reaches ATTACHED alongside SENT"
Task: "Integration test: Case Material failure-point — Progression unavailable doesn't affect email dispatch"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (Setup) and Phase 2 (Foundational — the full current-state pipeline).
2. Complete Phase 3 (User Story 1).
3. **STOP and VALIDATE**: run `quickstart.md`'s FIRST_SHARE smoke test independently.
4. CIMD-4548 is deployable at this point without CIMD-4543.

### Incremental Delivery

1. Setup + Foundational → pipeline skeleton ready.
2. Add User Story 1 → validate independently → deploy/demo (MVP).
3. Add User Story 2 → validate independently (including that the amend-reshare path has no shortCode gate at
   all) → deploy/demo.
4. Add User Story 3 (FR-020 Case Material attachment) → validate independently (a Progression failure must never
   affect email dispatch, and vice versa) → deploy/demo.
5. Polish (coverage, PMD, ADR, RBAC, go-live dependency re-checks including D13's Progression system user) before
   any real-environment release.

## Notes

- `[P]` tasks touch different files with no unmet dependency.
- Every class in plan.md's Source Code tree must reach 100% unit coverage (constitution Principle VII; not
  optional in this project, unlike the tasks-template's default).
- This feature adds **no new inbound endpoint** — CLAUDE.md's "integration test per new endpoint" rule is
  satisfied by the 5 integration tests per new *flow* instead (T038–T041, extended by T049; T059 for FR-020's new
  outbound integration).
- Do not start US2's implementation tasks (T052–T055) before US1's Foundational-dependent tests (T038–T043) are
  green, even though they touch different test methods in some shared files — avoids rebasing churn, not a hard
  technical dependency.
- US3 (T056–T068) touches no file US1/US2 own except `DocGeneratorEventListener` (T067, an additive fan-out
  change) — it can start as soon as Foundational (specifically T033) is green, independent of US2's progress.
