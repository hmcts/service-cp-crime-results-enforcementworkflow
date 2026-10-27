# Tasks: Triggering NOWS Generation for Enforcement Hearings

**Input**: Design documents from `specs/003-enforcement-nows-trigger/` (plan.md, spec.md, research.md, data-model.md,
contracts/, quickstart.md, ADR `specs/001-cimd-4246-hearing-resulted-to-libra/adrs/002-CIMD-4530-gob-data-on-public-event.md`)
**Prerequisites**: plan.md, spec.md (Clarifications 2026-10-19/20/22), constitution 1.2.1
**Tests**: MANDATORY (constitution VII): tests are written first and MUST fail before the implementation; an IT per new
endpoint and event (CLAUDE.md); the IT suites MUST be green (W `./gradlew build`; H `./runIntegrationTests.sh`).
**Organization**: by user story (spec.md US1–US4); each story is a testable increment.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US4 from spec.md

## Path Conventions

- **W** (this repo): `src/main/java/uk/gov/hmcts/cp/…`, `src/test/java/uk/gov/hmcts/cp/…`, `src/main/resources/…`
- **H**: `/Users/macpro/HMCTS/github/cpp-context-hearing-nows/…` (prefix `H:`); branch: the developer's (currently `dev/CIMD-3063`)
- **FA**: `/Users/macpro/HMCTS/github/cpp-context-azure-legalaidagency/azure-functions/durable-functions/…` (prefix `FA:`); branch created by the developer
- **C**: existing contract repo `/Users/macpro/HMCTS/github/api-cp-crime-results-enforcementgateway/…` (prefix `C:`)
- **W** stays on the checked-out branch (`dev/CIMD-3063`); the assistant creates no branches.
- Branches are created and managed by the developer (no branch changes, commits or pushes by the assistant).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: plan artifact, contract, dependencies, configuration.

- [X] T001 Implementation-plan HTML artifact via `skills/export-design-artifact/` in `docs/pipeline/artifacts/` before coding starts (CLAUDE.md hard rule)
- [X] T002 Add `POST /enforcement-notifications` (schemas `EnforcementNotification`, `EnforcementNow`, `NotificationAccepted`, `ErrorResponse`) from `specs/003-enforcement-nows-trigger/contracts/enforcement-notification-openapi.yml` to `C:src/main/resources/openapi/openapi-spec.yml` under a new tag `enforcement-notification`, bump `info.version` to `0.2.0`, and run the repo's lint and build (the developer opens the PR and publishes a draft version, D1)
- [X] T003 Add dependencies to `build.gradle`: `uk.gov.hmcts.cp:task-manager-service:1.0.11` (feed `hmcts-lib`), `org.springframework.boot:spring-boot-starter-data-redis`, `com.azure:azure-identity`, the new draft version of the `api-cp-crime-results-enforcementgateway` jar (with the notification endpoint) as `implementation` and `apiSpec`; test: Testcontainers `redis` (Docker is already required locally for Postgres)
- [X] T004 [P] Add configuration to `src/main/resources/application.yaml`: `cp.results.base-url` (`RESULTS_URL`), `cp.results.cjscppuid` (`RESULTS_CJSCPPUID`), timeouts (R17); `cp.results-cache.enabled` (default `false`), `spring.data.redis.*` (host, port, SSL; no password); `cp.notification.allowed-system-users` (`NOTIFICATION_ALLOWED_SYSTEM_USERS`); `cp.notification.retry-durations` (`10s,60s,300s`) and `cp.notification.replay-schedule` (`15m,1h,4h,12h,24h`); `cp.messaging.public-event-topic` (`CP_MESSAGING_PUBLIC_EVENT_TOPIC`, default `public.event`), `cp.messaging.gob-data-event-name`, `cp.messaging.system-user`; `taskmanager.schema.enabled: false` and `job.executor.*`; remove `cp.hearing-nows.*` and the `hearing-resulted-*` listener properties
- [X] T005 [P] Add the configuration properties classes with validation in `src/main/java/uk/gov/hmcts/cp/config/NotificationProperties.java` and `src/main/java/uk/gov/hmcts/cp/config/ResultsProperties.java`, with tests `src/test/java/uk/gov/hmcts/cp/config/NotificationPropertiesTest.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: schema, entities, task manager, contracts copies. ⚠️ No user story work before this phase is done.

- [X] T006 Copy cp-task-manager 1.0.11 `db/taskmanager/V1__create_jobs_table.sql` verbatim to `src/main/resources/db/migration/V1.006__create_jobs_table.sql` with a "keep in sync; do not edit independently" header (R10)
- [X] T007 Copy `db/taskmanager/V2__alter-column-retry_attempts_remaining_nullable.sql` verbatim to `src/main/resources/db/migration/V1.007__alter_jobs_retry_attempts_remaining_nullable.sql`
- [X] T008 [P] Create `src/main/resources/db/migration/V1.008__create_enforcement_notification.sql` (data-model.md)
- [X] T009 [P] Create `src/main/resources/db/migration/V1.009__create_enforcement_notification_now.sql` (PK `request_id`)
- [X] T010 [P] Create `src/main/resources/db/migration/V1.010__add_notification_id_to_hearing_result_submission.sql` — **not needed (2026-10-20)**: the notification id is stored in the existing `correlation_id` (see data-model.md), so the existing unique key already gives one submission per notification, case and defendant
- [X] T011 [P] Create `src/main/resources/db/migration/V1.011__replace_submission_unique_key.sql` (drop `uq_hrs_correlation_case_defendant`, add `uq_hrs_notification_case_defendant`, `correlation_id` NULLable) — **not needed**: see T010
- [X] T012 [P] Create `src/main/resources/db/migration/V1.012__make_gob_sent_offence_correlation_id_nullable.sql` — **not needed**: `gob_sent_offence.correlation_id` holds the notification id (NOT NULL kept)
- [X] T013 [P] Create `src/main/resources/db/migration/V1.013__add_notification_id_to_gob_sent_offence.sql` — **not needed**: see T012
- [X] T014 Write the failing repository IT `src/test/java/uk/gov/hmcts/cp/repository/EnforcementNotificationRepositoryIntegrationTest.java`: save/find a notification with its NOWs, duplicate `request_id` rejected, `(notification_id, case_id, defendant_id)` unique, Hibernate `validate` passes with the `jobs` table and the library's `Job` entity — **passed 2026-10-21** (found and fixed a real defect: a repeated request id was merged into another notification instead of failing on the primary key; `EnforcementNotificationNowEntity` now implements `Persistable`) — superseded 2026-10-20: the notification id is stored in `correlation_id` (data-model.md, I4)
- [X] T015 [P] Create `src/main/java/uk/gov/hmcts/cp/entity/NotificationStatus.java` (states of data-model.md) and `src/main/java/uk/gov/hmcts/cp/entity/FailedStage.java`
- [X] T016 [P] Create `src/main/java/uk/gov/hmcts/cp/entity/EnforcementNotificationEntity.java` and `src/main/java/uk/gov/hmcts/cp/entity/EnforcementNotificationNowEntity.java`; add `notificationId` to `src/main/java/uk/gov/hmcts/cp/entity/HearingResultSubmissionEntity.java` and `src/main/java/uk/gov/hmcts/cp/entity/GobSentOffenceEntity.java` — superseded 2026-10-20: the notification id is stored in `correlation_id` (data-model.md, I4)
- [X] T017 Create `src/main/java/uk/gov/hmcts/cp/repository/EnforcementNotificationRepository.java` and `src/main/java/uk/gov/hmcts/cp/repository/EnforcementNotificationNowRepository.java` (until T014 passes)
- [X] T018 Configure cp-task-manager in `src/main/java/uk/gov/hmcts/cp/config/TaskManagerConfig.java` (entity/repository scanning of `uk.gov.hmcts.cp.taskmanager`, executor settings, schema off); a separate `TaskManagerConfigIntegrationTest` (a registered no-op `@Task` runs once) was planned — no separate test written: the configuration is exercised by every task in `NotificationFlowIntegrationTest`
- [X] T019 [P] Copy H's event schema (T021) and `nows-data-items.json` verbatim to `src/test/resources/contracts/hearingnows/public.enforcementworkflow.enforcement-nows-data-ready.json` and `src/test/resources/contracts/hearingnows/nows-data-items.json` ("keep in sync"), and add `src/test/java/uk/gov/hmcts/cp/support/GobDataEventContract.java` (validator) with `src/test/java/uk/gov/hmcts/cp/support/GobDataEventContractTest.java`
- [X] T020 [P] H: add Liquibase `H:hearingnows-persistence/hearingnows-liquibase/src/main/resources/liquibase/hearingnows-view-store-db-changesets/006-key-enforcement-nows-data-by-request-id.xml` (data-model.md) and extend `H:hearingnows-persistence/hearingnows-persistence-api/src/test/java/uk/gov/moj/cpp/hearing/persistence/repository/EnforcementNowsDataRepositoryTest.java` for request-id keyed rows (failing first)
- [X] T021 [P] H: add the schema `H:hearingnows-event-processor/src/yaml/json/schema/public.enforcementworkflow.enforcement-nows-data-ready.json` (from contracts/, `id` = `schema_uri`), copy `nows-data-items.json` next to it, and register the event in `H:hearingnows-event-processor/src/yaml/subscriptions-descriptor.yaml`

**Checkpoint**: schema, entities, task manager and contracts ready.

---

## Phase 3: User Story 1 - Enforcement NOWS generated with GOB data, triggered by the results-processing app (Priority: P1) 🎯 MVP

**Goal**: FA notifies W; W reads the results (Results API), sends to GOB, publishes GOB's data; H completes the held
NOWs by request id and generates them.

**Independent Test**: POST a notification for a synthetic Enforcement case (WireMock Results, gateway, reference data)
→ one GOB submission, one event that validates against H's schema with the notification's request ids; in H, a held
NOW with that request id is completed and generated (`EnforcementNowsIT`).

### Tests for User Story 1 (write first, MUST fail) ⚠️

- [X] T022 [P] [US1] Controller IT `src/test/java/uk/gov/hmcts/cp/integration/EnforcementNotificationApiIntegrationTest.java`: 202 + `notificationId` and a `jobs` row; repeat with same ids → 202 same id, no new job; partly known ids → 409; invalid body → 400; missing / unknown `CJSCPPUID` → 403; body never logged — **passed 2026-10-21**
- [X] T023 [P] [US1] Unit test `src/test/java/uk/gov/hmcts/cp/service/NotificationServiceTest.java` (validation, allow-list, one transaction: notification + NOWs + task)
- [X] T024 [P] [US1] Unit test `src/test/java/uk/gov/hmcts/cp/client/ResultsClientTest.java` (`MockRestServiceServer`: path, Accept media type, `CJSCPPUID`, `{}` → not ready, 5xx/timeout → transient, 401/403 → configuration failure) — as built: every Results failure (incl. 401/403) is "unavailable", retried then stored (FR-015); a separate configuration-failure class is part of T066
- [X] T025 [P] [US1] Unit test `src/test/java/uk/gov/hmcts/cp/service/HearingResultsSourceTest.java` (Results payload mapped to `HearingResultedEvent` with `hearingDay` from the notification; readiness rule; cache disabled path)
- [X] T026 [P] [US1] Unit test `src/test/java/uk/gov/hmcts/cp/messaging/GobDataEventPublisherTest.java` (TextMessage, `CPPNAME`, `_metadata.id/name/createdAt/context.user`, configurable topic, payload valid against the schema copy via `GobDataEventContract`, `nowsDataItems` verbatim incl. decimals)
- [X] T027 [P] [US1] Unit tests `src/test/java/uk/gov/hmcts/cp/service/ProcessNotificationTaskTest.java` and `src/test/java/uk/gov/hmcts/cp/service/PublishGobDataTaskTest.java` (status transitions to `SUBMITTED` → `PUBLISHED`; publish enqueued only after GOB accepted; GOB accepted but no account number, or no notification NOW for the selected defendant's master id → `PUBLISH_REJECTED` with the reason, nothing published)
- [X] T028 [US1] Flow IT `src/test/java/uk/gov/hmcts/cp/integration/NotificationFlowIntegrationTest.java`: notification → WireMock Results/reference data/gateway → one GOB POST → one event on embedded Artemis (consumer on the configured topic, selector on `CPPNAME`) valid against the schema; notification `PUBLISHED`; PII log assertion (constitution IV) — **passed 2026-10-21** (`NotificationFlowIntegrationTest`)
- [X] T029 [P] [US1] H: unit test `H:hearingnows-event-processor/src/test/java/uk/gov/moj/cpp/hearing/nows/event/processor/EnforcementNowsDataEventProcessorTest.java` (toggle off → drop; held NOW completed once; no NOW → kept; repeated event → no effect; master-defendant mismatch → logged and dropped; transient DB error → rethrow)
- [X] T030 [P] [US1] H: update `H:hearingnows-event-processor/src/test/java/uk/gov/moj/cpp/hearing/nows/service/EnforcementNowsDataServiceTest.java` and `H:hearingnows-event-processor/src/test/java/uk/gov/moj/cpp/hearing/nows/jobstore/tasks/CompleteEnforcementNowTaskTest.java` for request-id keying (no coverage check)
- [X] T031 [P] [US1] H: extend `H:hearingnows-event-processor/src/test/java/uk/gov/moj/cpp/hearing/nows/service/EnforcementNowsLogTest.java` to assert that `EnforcementNowsDataEventProcessor`, the re-keyed `CompleteEnforcementNowTask` / `ReleaseEnforcementNowsDataTask` and `AddNowDocumentRequestService` log ids, request ids and statuses only (no GOB data, account number or personal data) (constitution IV)
- [X] T032 [P] [US1] H: test in `H:hearingnows-command-api/src/test/java/uk/gov/moj/cpp/hearing/nows/command/api/service/AddNowDocumentRequestServiceTest.java`: a `WAITING_FOR_GOB` NOW whose request id has `KEPT` data is completed at once; non-enforcement and financial NOWs unchanged
- [X] T033 [US1] H: rewrite `H:hearingnows-integration-test/src/test/java/uk/gov/moj/cpp/hearing/nows/it/EnforcementNowsIT.java` to publish the public event (`NowHelper` pattern, `newPublicJmsMessageProducerClientProvider`): NOW then event; event then NOW; repeated event; toggle off; unknown request id released after retention; financial and non-enforcement NOWs unchanged; a held enforcement NOW is never expired (no maximum wait, FR-016) — **passed 2026-10-21**: 9 run, 2 skipped (retention / never-expire need a short retention JNDI value in cpp-developers-docker); added `eventWithTwoRequestIdsShouldCompleteBothNows`; whole H IT suite 24 tests, 0 failures
- [X] T034 [P] [US1] FA: tests `FA:OutboundNowsVariants/SetNowDocumentRequest/Mapper/NowDocumentRequest/test/NowDocumentRequestMapper.test.js` (request id on every NOW of an Enforcement case's defendant, none otherwise, flag off → as today) and `FA:OutboundNowsVariants/test/index.test.js` (returns the `{requestId, masterDefendantId}` list)
- [X] T035 [P] [US1] FA: tests `FA:NotifyEnforcementWorkflow/test/index.test.js` (URL from `ENFORCEMENT_WORKFLOW_API_BASE_URI`, `CJSCPPUID` from the existing `CJSCPPUID` setting (T093), body valid against the OpenAPI draft, rethrows on failure) and `FA:SetComplianceEnforcement/test/index.test.js` (Enforcement-case defendants' entries marked and left out of the GOB update only; changed 2026-10-22, R6)

### Implementation for User Story 1

- [X] T036 [US1] Implement `src/main/java/uk/gov/hmcts/cp/service/NotificationService.java` (validate, allow-list → 403, repeat → existing id, partial → 409, persist + enqueue `PROCESS_ENFORCEMENT_NOTIFICATION` in one transaction)
- [X] T037 [US1] Implement `src/main/java/uk/gov/hmcts/cp/controller/EnforcementNotificationController.java` implementing the generated `uk.gov.hmcts.cp.openapi.api.EnforcementNotificationApi` from C; error mapping in `src/main/java/uk/gov/hmcts/cp/controller/NotificationExceptionHandler.java` (no payload values in messages)
- [X] T038 [P] [US1] Implement `src/main/java/uk/gov/hmcts/cp/client/ResultsClient.java` and its `RestClient` builder bean in `src/main/java/uk/gov/hmcts/cp/config/RestClientConfig.java` (timeouts R17)
- [X] T039 [US1] Implement `src/main/java/uk/gov/hmcts/cp/service/HearingResultsSource.java` (Results only in this phase; cache added in T044)
- [X] T040 [US1] Adapt `src/main/java/uk/gov/hmcts/cp/service/HearingResultedProcessor.java`, `src/main/java/uk/gov/hmcts/cp/service/SubmissionKey.java` and `src/main/java/uk/gov/hmcts/cp/service/SubmissionStore.java` to take the notification (key `(notification_id, case_id, defendant_id)`, `notification_id` on submissions and sent offences, return an outcome for the notification); update `src/test/java/uk/gov/hmcts/cp/service/HearingResultedProcessorTest.java` and `src/test/java/uk/gov/hmcts/cp/service/SubmissionStoreIntegrationTest.java` — superseded 2026-10-20: the notification id is stored in `correlation_id` (data-model.md, I4)
- [X] T041 [P] [US1] Implement `src/main/java/uk/gov/hmcts/cp/messaging/CppEnvelope.java` and `src/main/java/uk/gov/hmcts/cp/messaging/GobDataEventPublisher.java` (+ `JmsTemplate` config in `src/main/java/uk/gov/hmcts/cp/config/JmsPublisherConfig.java`) — no `JmsPublisherConfig` needed: Spring Boot's auto-configured `JmsTemplate` (pub-sub) is used; the 10 s JMS send timeout of R17 is not set separately (Artemis client defaults; a failed send is retried by stage 2)
- [X] T042 [US1] Implement `src/main/java/uk/gov/hmcts/cp/service/ProcessNotificationTask.java` and `src/main/java/uk/gov/hmcts/cp/service/PublishGobDataTask.java` (`@Task`), and replace the REST hand-over in `src/main/java/uk/gov/hmcts/cp/service/NowsHandover.java` / `src/main/java/uk/gov/hmcts/cp/service/NowsHandoverPayloadBuilder.java` by the event payload (request ids instead of correlation id, hearing day and case fields); `PublishGobDataTask` records `PUBLISH_REJECTED` (reason in `last_error`) when nothing is publishable (data-model.md)
- [X] T043 [US1] Remove the REST hand-over: delete `src/main/java/uk/gov/hmcts/cp/client/HearingNowsClient.java`, `src/main/java/uk/gov/hmcts/cp/client/HandoverResult.java`, the `hearingNowsRestClientBuilder` in `RestClientConfig.java`, `src/test/java/uk/gov/hmcts/cp/client/HearingNowsClientTest.java`, `src/test/java/uk/gov/hmcts/cp/support/HearingNowsContract.java`, `src/test/java/uk/gov/hmcts/cp/support/HearingNowsContractTest.java`, `src/test/resources/contracts/hearingnows/hearingnows.add-enforcement-nows-data.json`, `src/test/resources/wiremock/mappings/hearing-nows-enforcement-nows-data.json`; update `NowsHandoverTest.java`, `NowsHandoverPayloadBuilderTest.java`
- [X] T044 [US1] Add the Results cache (FR-006, R8): `src/main/java/uk/gov/hmcts/cp/client/ResultsCacheClient.java` (key `INT_{hearingId}_{hearingDate}_result_`), `src/main/java/uk/gov/hmcts/cp/config/RedisConfig.java` (Entra ID token credentials, no password; bean only when `cp.results-cache.enabled=true`), used first by `HearingResultsSource`; tests `src/test/java/uk/gov/hmcts/cp/client/ResultsCacheClientTest.java` and a cache-hit/miss case (in `src/test/java/uk/gov/hmcts/cp/integration/ResultsCacheIntegrationTest.java`, not `NotificationFlowIntegrationTest`)
- [X] T045 [US1] Remove the hearing-resulted listener (FR-005): delete `src/main/java/uk/gov/hmcts/cp/messaging/HearingResultedEventListener.java`, `src/main/java/uk/gov/hmcts/cp/messaging/HearingResultedJmsConfig.java`, `src/test/java/uk/gov/hmcts/cp/messaging/HearingResultedEventListenerTest.java`, `src/test/java/uk/gov/hmcts/cp/integration/JmsHearingResultedIntegrationTest.java`; keep `PublicEventLoggingListener.java`
- [X] T046 [US1] Re-drive the scenario suite through the API: change `src/test/java/uk/gov/hmcts/cp/integration/EmbeddedBrokerIntegrationTestBase.java` and `src/test/java/uk/gov/hmcts/cp/integration/ScenarioIntegrationTest.java` to stub Results with the scenario's event fixture, POST a notification, await the notification's final state, and assert on published events instead of WireMock hearing-nows POSTs; update `src/test/java/uk/gov/hmcts/cp/integration/WorkflowStubsIntegrationTestBase.java` and the flow ITs (`HearingResultedFlowIntegrationTest.java`, `NowsHandoverFailureIntegrationTest.java`, `NowsDataRequestIntegrationTest.java`, `FailureOutcomesIntegrationTest.java`, `PaymentDueDateNoneIntegrationTest.java`) and `src/test/resources/scenarios/*/scenario.json` (`handover` → `published` expectations with request ids) — **done 2026-10-20, passed 2026-10-21 (41 scenarios; each now also asserts no stored failure and one PUBLISHED notification per published event)**: scenarios driven by `notifyShare` + Results stub; `published` expectations replace `handover`; scenarios 29, 41, 44 retired (REST hand-over failure and missing hearing day no longer exist; publish failure is covered by T063); `NowsHandoverFailureIntegrationTest` removed for the same reason (44 reused 2026-10-22 for reshare-before-first-share)
- [X] T047 [US1] H: implement `H:hearingnows-event-processor/src/main/java/uk/gov/moj/cpp/hearing/nows/event/processor/EnforcementNowsDataEventProcessor.java` (`@Handles`, `FeatureControlGuard`, ids-only logging) using `H:hearingnows-event-processor/src/main/java/uk/gov/moj/cpp/hearing/nows/service/EnforcementNowsDataService.java` re-keyed by request id
- [X] T048 [US1] H: re-key `H:hearingnows-persistence/hearingnows-persistence-api/src/main/java/uk/gov/moj/cpp/hearing/nows/persistence/entity/EnforcementNowsDataEntity.java`, `H:hearingnows-persistence/hearingnows-persistence-api/src/main/java/uk/gov/moj/cpp/hearing/nows/persistence/repository/EnforcementNowsDataRepository.java`, `H:hearingnows-event-processor/src/main/java/uk/gov/moj/cpp/hearing/nows/jobstore/tasks/CompleteEnforcementNowTask.java`, `H:hearingnows-event-processor/src/main/java/uk/gov/moj/cpp/hearing/nows/jobstore/tasks/ReleaseEnforcementNowsDataTask.java`, `H:hearingnows-event-processor/src/main/java/uk/gov/moj/cpp/hearing/nows/service/EnforcementNowsTasks.java` by request id
- [X] T049 [US1] H: kept-data check for new `WAITING_FOR_GOB` NOWs in `H:hearingnows-command-api/src/main/java/uk/gov/moj/cpp/hearing/nows/command/api/service/AddNowDocumentRequestService.java`; remove the feature 002 hold (`H:hearingnows-event-processor/src/main/java/uk/gov/moj/cpp/hearing/nows/service/EnforcementNowsHold.java`, its test and its call site)
- [X] T050 [US1] H: remove the REST hand-over command: `H:hearingnows-command-api/src/main/java/uk/gov/moj/cpp/hearing/nows/command/api/AddEnforcementNowsDataCommandApi.java`, its RAML resource in `H:hearingnows-command-api/src/raml/hearingnows-command-api.raml`, schema + example, DRL `add-enforcement-nows-data-command-rules.drl`, `RuleConstants.addEnforcementNowsDataGroups`, tests `AddEnforcementNowsDataCommandApiTest.java`, `AddEnforcementNowsDataAccessControlTest.java`, `AddEnforcementNowsDataSchemaTest.java`, `ApiRestEndpoint.ENFORCEMENT_NOWS_DATA`
- [X] T051 [P] [US1] FA: enforcement rule helper `FA:CommonUtility/EnforcementCase.js` (OU code = `ENFORCEMENT_AUTHORITY_CODE`, default `GAPGD00`) with `FA:CommonUtility/EnforcementCase.test.js`
- [X] T052 [US1] FA (superseded by T092): request ids in `FA:OutboundNowsVariants/SetNowDocumentRequest/Mapper/NowDocumentRequest/NowDocumentRequestMapper.js` and the list output in `FA:OutboundNowsVariants/index.js` (flag `EnforcementNows` via `FA:CommonUtility/FeatureFlagClient.js`)
- [X] T053 [US1] FA (superseded by T092): mark Enforcement-case defendants' compliance entries in `FA:SetComplianceEnforcement/index.js` and leave them out of the GOB update in the orchestrator only (FR-017); NCES and NOW variants keep the full list (FR-021; changed 2026-10-22 from "skip", R6)
- [X] T054 [US1] FA: new activity `FA:NotifyEnforcementWorkflow/index.js` (registered like the other activities) and its call after `ProcessOutboundNowVariants` in `FA:HearingResultedNowsHandler/index.js`

**Checkpoint**: US1 works end to end with Results only (cache off) and first shares.

---

## Phase 4: User Story 2 - Each offence's first-time results reach GOB once; amend & reshare never (Priority: P1)

**Goal** (decisions of 2026-10-22, research R9, R21, R22): a first-time share sends each offence once, with every result
code; an amend & reshare is never sent to GOB (reshare indicator from the notification) and reuses the earlier GOB data,
or ends `NO_GOB_DATA` with its NOWS held.

**Independent Test**: first share (sent), then its reshare (not sent, published with `gobDataReused=true` and its own
request ids); a reshare with no earlier GOB data → `NO_GOB_DATA`, nothing published; a share with codes outside GOB's old
list → all sent; repeated notification → no effect.

### Tests for User Story 2 (write first, MUST fail) ⚠️

- [X] T055 [P] [US2] Update `src/test/java/uk/gov/hmcts/cp/service/ResultCodeResolverTest.java`: every code sent (also codes outside GOB's old list), only unknown definitions and codes over 10 characters dropped and reported (FR-010, R21) — done 2026-10-22
- [X] T056 [P] [US2] Update `src/test/java/uk/gov/hmcts/cp/service/HearingResultedProcessorTest.java`, `NowsHandoverTest.java`, `HearingResultsSourceTest.java`, `ProcessNotificationTaskTest.java`, `NotificationServiceTest.java`: reshare never sent; reshare with earlier GOB data → reuse; without → `NO_GOB_DATA`; reshare flag from the notification, stored, part of the repeat check (R22) — done 2026-10-22
- [ ] T057 [US2] Scenarios under `src/test/resources/scenarios/`: restore the reshare scenarios 02, 14, 30, 31, 32 to "never sent" (reshare flag now in the notification); add reshare-before-first-share (reshare `NO_GOB_DATA`, delayed first share sent), repeated-notification, new-request-ids-for-already-sent-offence, two-offences-one-already-sent; update the code-list scenarios (07, 15, 16, 17 and the expected requests) to "every code sent" (FR-010); API IT for the required `isReshare` (400) and a repeat with another flag (409) (2026-10-22: 02, 14, 30–32 restored, 17/39 updated, 44 added, API IT 400/409 for isReshare done; open: new-request-ids-for-already-sent-offence, two-offences-one-already-sent) — 2026-10-22 also added 45 (later share with no result code publishes nothing, T059) and 46 (reshare redelivered, published once); two-offences-one-already-sent is covered by 33; still open: new-request-ids-for-already-sent-offence (needs runner support for a second key)

### Implementation for User Story 2

- [X] T058 [US2] Keep the reshare branch (`EnforcementCaseSelector`, `HearingResultedProcessor.processReshare`) and feed it the notification's flag: `is_reshare` on `enforcement_notification` (V1.008, not yet released), `NotificationService` stores it and checks it on repeats, `HearingResultsSource` maps it, a reshare without earlier GOB data ends `NO_GOB_DATA` (`NowsHandover.noGobData`) — done 2026-10-22 (replaces the 2026-10-20 "remove the reshare branch")
- [X] T059 [US2] Apply FR-010 to the no-code case in `src/main/java/uk/gov/hmcts/cp/service/HearingResultedProcessor.java` (no result with a code, or only codes over 10 characters → `SKIPPED_NO_RESULT_CODE`, GOB not contacted and nothing sent to the NOWS service; feature 002 FR-020's reuse of the earlier GOB data is dropped) — done 2026-10-22
- [X] T084 [US2] Contract `api-cp-crime-results-enforcementgateway` (0.2.0, unpublished): `EnforcementNotification.isReshare` required; `HearingResult.resultCode` enum removed (maxLength 10, agreed with GOB/Libra); `OpenApiObjectsTest` updated — done 2026-10-22; spec copy `contracts/enforcement-notification-openapi.yml` and W's Libra copy (local amendment) aligned
- [X] T085 [US2] W: `ResultCodeResolver`, `ResolvedResult`, `ResolvedCodes` send every code (String instead of the generated enum), codes over 10 characters dropped and reported, never truncated (FR-010, R21) — done 2026-10-22
- [X] T086 [P] [US2] FA: `NotifyEnforcementWorkflow` sends `isReshare` (`hearingResultedObj.isReshare === true`), orchestrator passes it; tests and the FA schema copy updated — done 2026-10-22
- [ ] T087 [US2] Gateway service `service-cp-crime-results-enforcementgateway`: move to the contract version with the open `resultCode` (its generated model rejects codes outside the old enum) before W sends such codes (plan D7)
- [X] T088 [US2] FA/H: test that an amend & reshare's enforcement NOWS follow the first-time decision (no suppression added: FA regenerates only amended NOWS; W reuses first-time GOB data; without it the NOWS stay held) (FR-021) — FA part done 2026-10-23: `FA:OutboundNowsVariants/test/index.test.js` ("amend & reshare of an Enforcement case (T088)": flag on regenerates exactly the NOWs flag off does, only the amended one, new request id, only it listed) and `FA:HearingResultedNowsHandler/index.test.js` (NCES/NOW-variant/subscription inputs identical flag on vs off on a reshare); H part open — W/H part covered 2026-10-23: W scenarios 30/32 (reshare reuses the first-time GOB data), 02/14/31/44 (no first-time GOB data: NO_GOB_DATA, nothing published), `NowsHandoverTest` (waits while the first share is in progress); H `EnforcementNowsIT.reusedGobDataShouldCompleteTheReshareNowLikeNewData`, `heldEnforcementNowShouldNeverExpire` and the no-expiry unit tests
- [X] T089 [US2] Simulator `enforcement-workflow-simulator`: accept any `resultCode` of 1–10 characters as the agreed Libra does (contract copy amended like W's; the 42 published codes kept as a frozen list for the catalogue checks; unknown code → 200 with no extra NOWS fields; blank or > 10 → 400) — done 2026-10-22
- [X] T092 [US1] FA isolation (tech lead review 2026-10-26, research R25): `EnforcementNows` flag removed, `FeatureFlagClient`, NOWS triggers, `SetComplianceEnforcement`, `OutboundNowsVariants` + mapper restored to main; new activities `FA:EnforcementCaseNowsVariants/index.js`, `FA:ProcessEnforcementCaseNowsVariants/index.js` (+ tests), `partitionByEnforcementCase` in `FA:CommonUtility/EnforcementCase.js`; routing, GOB filter and notification in `FA:HearingResultedNowsHandler/index.js` (+ tests) — done 2026-10-26; H's `EnforcementNows` toggle removed as well (`EnforcementNowsDataEventProcessor`, `Constants.FEATURE_ENFORCEMENT_NOWS`, toggle-off unit tests and `EnforcementNowsIT.eventShouldBeDroppedWhenTheToggleIsOff`)
- [X] T093 [US1] FA: `NotifyEnforcementWorkflow` sends the app's existing system user (setting `CJSCPPUID`) instead of the new `ENFORCEMENT_WORKFLOW_CJSCPPUID`, removed from `FA:local.settings.json` (tech lead review 2026-10-28, research R2); tests in `FA:NotifyEnforcementWorkflow/test/index.test.js`
- [X] T094 [US1] Smallest diff to main (tech lead review 2026-10-28, research R26): `FA:HearingResultedNowsHandler/index.js` keeps `nowsVariantsSubscriptions` / `complianceEnforcementArray` (narrowed, no new names); H: `AddNowDocumentRequestCommandApi` keeps its `WAITING_FOR_GOB` line and calls `AddNowDocumentRequestService.completeIfGobDataAlreadyReceived` (was `waitForGob`), `WAITING_FOR_ENFORCEMENT_DATA` and `NowDocumentRequestEntity.masterDefendantId` removed, cosmetic changes reverted; tests updated (command API, service, repository, model)
- [X] T095 [US1] FA: no financial branch in the enforcement logic (tech lead review 2026-10-28, research R27): `FA:EnforcementCaseNowsVariants/index.js` reuses `OutboundNowsVariants` unchanged and gives every returned NOW request a new request id; tests in `FA:EnforcementCaseNowsVariants/test/index.test.js` (NOWs with and without financial results: new request id, content as `OutboundNowsVariants` builds it)
- [X] T096 [US1] H naming (tech lead review 2026-10-28): `AddNowDocumentRequestService.completeIfGobDataAlreadyReceived` (the NOW waits in `WAITING_FOR_GOB`; completed at once when the GOB data event came first) and the event handler `EnforcementNowsDataEventProcessor.processEnforcementNowsDataReady` (H's `process…` handler convention); tests and docs updated
- [X] T091 [US2] W: reshare without earlier GOB data waits (retry → `STORED_FAILURE`, `FIRST_SHARE_IN_PROGRESS`) while a first-time share of the hearing is RECEIVED/PROCESSING/STORED_FAILURE (`FirstShareInProgressException`, `NotificationStore.firstShareInProgress`); tests `NowsHandoverTest`, `ProcessNotificationTaskTest`, `EnforcementNotificationRepositoryIntegrationTest` — done 2026-10-22 (automatic replay depends on T067)
- [ ] T060 [US2] Remove `src/main/java/uk/gov/hmcts/cp/service/ShareCorrelationId.java` and `src/test/java/uk/gov/hmcts/cp/service/ShareCorrelationIdTest.java`; stop writing `correlation_id`
- [X] T061 [P] [US2] H: remove `ShareCorrelationId` (`H:hearingnows-domain-common/src/main/java/uk/gov/moj/cpp/hearing/nows/domain/common/ShareCorrelationId.java`), `H:hearingnows-event-processor/src/main/java/uk/gov/moj/cpp/hearing/nows/service/NowShareCorrelationResolver.java`, `EnforcementNowCoverage.java`, `ExpireWaitingEnforcementNowTask.java` and their tests; stop writing `WAITING_FOR_ENFORCEMENT_DATA` — done 2026-10-22: all four classes, `ExpireWaitingEnforcementNowJobData`, task name `hearingnows.expire-waiting-enforcement-now`, `EnforcementNowsDataRepository.findByKey` and their tests removed; nothing writes `WAITING_FOR_ENFORCEMENT_DATA` (enum value kept, documented as read-only for old rows); no-expiry proof `NowDocumentRequestTaskNamesTest`, `ReleaseEnforcementNowsDataTaskTest.releaseShouldNeverTouchAHeldNow`

**Checkpoint**: US1 + US2 pass; no offence is sent twice in any scenario.

---

## Phase 5: User Story 3 - No message is lost when a step fails (Priority: P1)

**Goal**: 3 retries with back-off per stage, stored failure, automatic and manual replay; unknown GOB outcome never
auto-retried; FA run marked failed when W is unreachable.

**Independent Test**: Results unavailable then available → completes; broker unavailable → `STORED_FAILURE`
(`PUBLISH`) → replay publishes without a GOB call; gateway timeout → `GOB_FAILED`, no retry.

### Tests for User Story 3 (write first, MUST fail) ⚠️

- [ ] T062 [P] [US3] Unit test `src/test/java/uk/gov/hmcts/cp/service/NotificationFailureClassifierTest.java` (transient vs configuration vs unknown-outcome vs rejection, R11)
- [ ] T063 [P] [US3] Unit tests `src/test/java/uk/gov/hmcts/cp/service/ReplayNotificationTaskTest.java` and `src/test/java/uk/gov/hmcts/cp/service/NotificationReplaySweeperTest.java` (schedule, limit, manual `next_replay_at`, publish replay never calls GOB)
- [ ] T064 [US3] IT `src/test/java/uk/gov/hmcts/cp/integration/NotificationFailureIntegrationTest.java`: Results 503 then 200; broker down for stage 2 → stored failure → replay → published once; gateway timeout → `GOB_FAILED`, no second GOB POST; manual replay via `next_replay_at`
- [X] T065 [P] [US3] FA: test in `FA:HearingResultedNowsHandler/index.test.js` (create if missing): notify activity failing after retries → orchestration throws (Failed); other activities' handling unchanged

### Implementation for User Story 3

- [ ] T066 [US3] Implement `src/main/java/uk/gov/hmcts/cp/service/NotificationFailureClassifier.java` and use it in `ProcessNotificationTask.java` / `PublishGobDataTask.java` (`getRetryDurationsInSecs` from `cp.notification.retry-durations`; on exhaustion → `STORED_FAILURE` with `failed_stage`, `last_error`, `next_replay_at`; reference data unavailable becomes retryable: flip scenario 10's `notificationStatuses` from `GOB_FAILED`; give a skipped share with no shared time a state of its own instead of `SKIPPED_NOT_ENFORCEMENT`, scenario 42)
- [ ] T067 [US3] Implement `src/main/java/uk/gov/hmcts/cp/service/ReplayNotificationTask.java` and `src/main/java/uk/gov/hmcts/cp/service/NotificationReplaySweeper.java` (1-minute sweep of due `next_replay_at`, replay schedule and limit, resume the failed stage only; increment `replay_count` when queuing a replay; reclaim stale jobs and `PROCESSING` notifications, OP-14)
- [ ] T068 [US3] Keep `src/main/java/uk/gov/hmcts/cp/service/StaleSubmissionSweeper.java` working for task-driven sends (javadoc no longer refers to JMS redelivery) and mark the notification `GOB_FAILED` for interrupted sends; update `src/test/java/uk/gov/hmcts/cp/service/StaleSubmissionSweeperTest.java`
- [X] T069 [US3] FA: rethrow at the end of `FA:HearingResultedNowsHandler/index.js` when `NotifyEnforcementWorkflow` finally failed (FR-002a)

**Checkpoint**: failure scenarios recover or are stored and visible; no duplicate GOB submission.

---

## Phase 6: User Story 4 - Support can see what happened to each notification (Priority: P2)

**Goal**: every notification's outcome findable by hearing and request id, without payloads or PII.

**Independent Test**: successful, skipped and failed notifications each show their final state and reason via the
runbook queries.

- [ ] T070 [P] [US4] Test `src/test/java/uk/gov/hmcts/cp/integration/NotificationOutcomeIntegrationTest.java`: each final state of data-model.md recorded with `submission_id`/`gob_data_reused`/`failed_stage`/`last_error` as applicable; logs carry `notificationId` and request ids only
- [ ] T071 [US4] Structured log lines (JSON, ids and states) at each transition in `NotificationService.java`, `ProcessNotificationTask.java`, `PublishGobDataTask.java`, `ReplayNotificationTask.java`
- [ ] T072 [US4] Support runbook in `README.md` (queries from quickstart.md, manual replay, meaning of each state, held NOWS after `GOB_FAILED` → CIMD-4259)

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T073 [P] Update `README.md` (trigger, Results/cache, task manager, event publishing, env vars; remove listener and `HEARING_NOWS_*`), `docker-compose.yml` (no `HEARING_NOWS_URL`; `RESULTS_URL`, topic name) and WireMock mappings under `src/test/resources/wiremock/mappings/` (Results internal hearing details) — partly done 2026-10-22/23: `RESULTS_URL`, allow-list, Results stub, `HEARING_NOWS_*` removed, topic env (`CP_MESSAGING_PUBLIC_EVENT_TOPIC`)
- [ ] T074 [P] Mark superseded parts in `specs/001-cimd-4246-hearing-resulted-to-libra/spec.md` and `specs/002-enforcement-nows-generation/spec.md` (status line + "superseded by 003" notes per the table in 003 spec.md; no rewrite)
- [ ] T075 [P] List the deployment values for D5 in `specs/003-enforcement-nows-trigger/quickstart.md` (W, FA settings; removals) for the developer to apply in `cpp-aks-deploy` (read-only here)
- [ ] T076 Security boundary (FR-003, constitution VIII): add to `specs/003-enforcement-nows-trigger/quickstart.md` the deployment requirement that W's notification endpoint is exposed only on the internal ingress (no public route, no APIM product) and a verification step for each environment (call from outside the cluster fails; call from the Function App's network with an allow-listed `CJSCPPUID` gets 202, with an unknown one 403); owner: developer with the platform team (applied in `cpp-aks-deploy`)
- [ ] T077 Production gate for FR-020: add to `specs/003-enforcement-nows-trigger/quickstart.md` a release checklist item "feature 003 (W, H and FA; no feature flags, R25) MUST NOT be released to production until `specs/001-cimd-4246-hearing-resulted-to-libra/adrs/002-CIMD-4530-gob-data-on-public-event.md` is Accepted", and record the ADR status in `specs/003-enforcement-nows-trigger/research.md` OP-6 when it changes
- [ ] T078 Before release (constitution II): replace the draft `api-cp-crime-results-enforcementgateway` version in `build.gradle` (`implementation` and `apiSpec`) with the published fixed `X.Y.Z` version and confirm `validateApiSpecVersions` passes
- [ ] T079 Run `./gradlew build` (unit, IT, PMD) green; fix violations without suppressions unless justified inline
- [ ] T080 H: `mvn -o clean install` with JDK 17 and `./runIntegrationTests.sh` green (not in parallel with W builds)
- [ ] T081 FA: `npm test` green
- [ ] T082 Validate quickstart.md steps locally (W smoke test, H IT, FA tests) and record the result in this file
- [ ] T083 SC-006: in the dev environment, measure for 3 enforcement shares the time from the share to the NOW reaching `DOCUMENT_REQUESTED` (W `received_at`→`PUBLISHED`, H `ENFORCEMENT_DATA_ADDED`→generated) and record the results in `specs/003-enforcement-nows-trigger/quickstart.md`; target ≤ 10 minutes
- [ ] T090 **Before merging to main**: roll back / simplify the TEMPORARY payload diagnostics of 2026-10-23 (research I19, exception to FR-019 / constitution IV): W — delete `src/main/java/uk/gov/hmcts/cp/diagnostics/` (`TempPayloadLog`, `TempNotificationTrafficFilter`), every call marked `// TEMP DIAGNOSTICS`, `src/test/java/uk/gov/hmcts/cp/support/TempDiagnosticsLogFilter.java` and its uses (restore plain `output.getAll()`), the diagnostics assertion in `NotificationFlowIntegrationTest`, and decide on the CIMD-4246 `client/TempDiagnostics` lines; H — the same for its `TempDiagnostics` class, call sites and test filters (list in I19); gateway `LibraClient` and `ProsecutionCaseClient` TEMP DIAGNOSTICS likewise. Re-run the PII-in-log tests unfiltered

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup (T001–T005) → Foundational (T006–T021) → US1 (T022–T054) → US2 (T055–T061, T084–T089, T091) → US3 (T062–T069) → US4 (T070–T072) → Polish (T073–T083, T090)
- **T001 (plan artifact) MUST be done before any code task**, per CLAUDE.md.
- T037 needs T002's generated API (D1); until the draft is published, T037 can be written against a locally published C jar (`publishToMavenLocal`).

### User Story Dependencies

- US1: needs Phase 2.
- US2: needs US1's notification-driven processor (T040, T042).
- US3: needs US1's tasks (T042) and US2's outcomes.
- US4: needs US1–US3 states.

### Within Each User Story

- Tests first and failing, then models/entities, then services, then entry points, then integration.
- H and FA tasks of a story run in parallel with W tasks (different repos).

### Parallel Opportunities

- T004/T005; T008–T013; T015/T016; T019/T020/T021.
- US1 tests T022–T027, T029–T032, T034–T035 together; T038/T041/T051 in parallel.
- US2: T055/T056/T061. US3: T062/T063/T065.

## Parallel Example: User Story 1

```text
Task: "Controller IT EnforcementNotificationApiIntegrationTest.java" (T022)
Task: "ResultsClientTest.java" (T024)
Task: "GobDataEventPublisherTest.java" (T026)
Task: "H: EnforcementNowsDataEventProcessorTest.java" (T029)
Task: "FA: NowDocumentRequestMapper.test.js" (T034)
```

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 + Phase 2 (+ T001 before code).
2. US1 with Results only and the cache switched off.
3. Validate: W flow IT, H `EnforcementNowsIT`, FA tests.

### Incremental Delivery

1. US1 → US2 (once-per-offence rule) → US3 (failures, replay) → US4 (support).
2. Cache switched on per environment when D3 is done.
3. Joint release of W, H and FA per environment (D6); no feature flags (R25).
