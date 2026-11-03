# Tasks: Enforcement NOWS Generation with GOB Account Data

**Input**: Design documents from `specs/002-enforcement-nows-generation/` (spec.md rev. 2026-10-09, plan.md, research.md F1-F18 / R1-R21 / OQ-1..OQ-13, data-model.md, contracts/, quickstart.md scenarios 1-21, analysis.md)
**Updated**: 2026-10-09 (second round): OQ-10 to OQ-12 answered (non-offence results once; adjourned offences never sent, only final results; nothing new → reuse the most recent GOB data, also for NOWS DF issues again); payload flag `reshare` renamed `gobDataReused`; OQ-13 added.
**Prerequisites**: plan.md, spec.md (US1-US6), research.md, data-model.md, contracts/
**Regenerated**: 2026-10-09, after the tech lead agreement (enforcement-specific hearing-nows command; share correlation id = UUID computed in W and H) and the developer decision "each offence is sent to GOB at most once; a later share of the same hearing sends only the newly resulted offences" (US6, FR-018 to FR-021). Previous version (2026-10-08, T001-T063) superseded.
**Updated**: 2026-10-09 (third round): interim correlation attribute = **hearing day** (W: event `hearingDay`; H: `nowContent.orderDate`), to be revisited (research OQ-9); all BLOCKED marks removed; same-day reshares share the day's id (H versions kept data by `sharedTime`); the "same type of result" check applies to amended NOWs.

**Tests**: MANDATORY and written first; each must fail before its implementation (constitution VII).
**Commits**: none by the implementer; the developer commits and raises PRs (W and H separately).
**Implementation status (2026-10-13, after the reviews below)**: all stories implemented and verified. W: `./gradlew clean build pmdMain pmdTest :enforcement-workflow-simulator:pmdMain` green (470 tests: 266 service, 110 of them integration, + 204 simulator; PMD clean). H (JDK 17): `mvn clean install` green (250 unit tests) and the IT suite green (30 ITs: 28 run, 2 short-period cases skipped; `EnforcementNowsIT` 17 with 15 run, and the existing `HearingNowsIT` 8, `AddNowDocumentRequestIT` 3, `HearingNowsWithHRDSIT` 2). The IT run found one defect in H's existing file-service step (research F19), fixed. Open: T005/T006 (answers from the BA/tech lead). T028 (g) and T067 run with `-DenforcementNowsShortPeriods=true` against short JNDI periods (see `EnforcementNowsIT`).
**Blocked**: none. The correlation attribute is interim (OQ-9); it is isolated in `ShareCorrelationId` (T020, T021) and `NowShareCorrelationResolver` (T035), so a later change touches only those, their tests and the contract's vectors.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1-US6 (spec.md)

## Path Conventions

| Prefix | Path |
|---|---|
| `W` | `service-cp-crime-results-enforcementworkflow/` (this repository; paths below are relative to its root) |
| `Wm` / `Wt` | `src/main/java/uk/gov/hmcts/cp/` / `src/test/java/uk/gov/hmcts/cp/` |
| `H` | `../cpp-context-hearing-nows/` |
| `Hcmd` | `../cpp-context-hearing-nows/hearingnows-command-api/` |
| `Hcmdj` | `../cpp-context-hearing-nows/hearingnows-command-api/src/main/java/uk/gov/moj/cpp/hearing/nows/command/api/` |
| `Hcmdt` | `../cpp-context-hearing-nows/hearingnows-command-api/src/test/java/uk/gov/moj/cpp/hearing/nows/command/api/` |
| `Hdom` | `../cpp-context-hearing-nows/hearingnows-domain-common/src/main/java/uk/gov/moj/cpp/hearing/nows/domain/common/` |
| `Hdomt` | `../cpp-context-hearing-nows/hearingnows-domain-common/src/test/java/uk/gov/moj/cpp/hearing/nows/domain/common/` |
| `Hjob` | `../cpp-context-hearing-nows/hearingnows-event-processor/src/main/java/uk/gov/moj/cpp/hearing/nows/jobstore/` |
| `Hjobt` | `../cpp-context-hearing-nows/hearingnows-event-processor/src/test/java/uk/gov/moj/cpp/hearing/nows/jobstore/` |
| `Hsvc` | `../cpp-context-hearing-nows/hearingnows-event-processor/src/main/java/uk/gov/moj/cpp/hearing/nows/service/` |
| `Hsvct` | `../cpp-context-hearing-nows/hearingnows-event-processor/src/test/java/uk/gov/moj/cpp/hearing/nows/service/` |
| `Hper` | `../cpp-context-hearing-nows/hearingnows-persistence/hearingnows-persistence-api/src/main/java/uk/gov/moj/cpp/hearing/nows/persistence/` |
| `Hlqb` | `../cpp-context-hearing-nows/hearingnows-persistence/hearingnows-liquibase/src/main/resources/liquibase/hearingnows-view-store-db-changesets/` |
| `Hit` | `../cpp-context-hearing-nows/hearingnows-integration-test/src/test/java/uk/gov/moj/cpp/hearing/nows/` |

Build/test: W `./gradlew clean build pmdMain :enforcement-workflow-simulator:pmdMain` (Postgres on 5432). H `mvn clean install` and `./runIntegrationTests.sh` (needs `CPP_DOCKER_DIR`). H follows its own conventions (constitution v1.1.1).

---

## Phase 1: Setup

- [X] T001 Confirm H's working tree is clean and up to date on `main` (`git -C ../cpp-context-hearing-nows status`; `pull --ff-only` only if clean); record the H base commit in `specs/002-enforcement-nows-generation/research.md` §1 (F6 row). The developer creates the H branch (suggested `dev/CIMD-4462-enforcement-nows`); do not commit.
- [X] T002 Fetch reference PR #186 read-only into `refs/pr/186` (`git -C ../cpp-context-hearing-nows fetch git@github.com:hmcts/cpp-context-hearing-nows.git refs/pull/186/head:refs/pr/186`) and list its files (`git -C ../cpp-context-hearing-nows diff --stat main...refs/pr/186`) as input to T007 and T012.
- [X] T003 [P] Add W configuration in `src/main/resources/application.yaml` under `cp.hearing-nows`: `base-url: ${HEARING_NOWS_URL:http://localhost:8080}`, `cjscppuid: ${HEARING_NOWS_CJSCPPUID:00000000-0000-0000-0000-000000000000}`, `connect-timeout-ms: 5000`, `read-timeout-ms: 10000` (research R9; comment: placeholder system user, OQ-5).
- [X] T004 [P] Add H JNDI defaults `hearingnows.enforcement.prosecution.authority.code` (`GAPGD00`), `hearingnows.enforcement.nows.max.wait` (`PT24H`), `hearingnows.enforcement.nows.data.retention` (`PT24H`) where H defines `hearingnows.task.retry.threshold.durations.seconds` (find it under `H` and follow it), and `FEATURE_ENFORCEMENT_NOWS = "EnforcementNows"` in `../cpp-context-hearing-nows/hearingnows-event-processor/src/main/java/uk/gov/moj/cpp/hearing/nows/util/Constants.java`.
- [ ] T005 [P] Verify OQ-2 (analysis G1): ask the BA / reference-data team for the live `financial` flag of the result definitions SC, CW, DW, COLLO, MPSO, SUMM, FSN, NBWT, CWN, REGF, REM, AEC, AEOC, BWTD, BWTU; record the answer in `specs/002-enforcement-nows-generation/research.md` (F4, OQ-2). If any is financial, raise it with the tech lead before go-live.
- [ ] T006 [P] Ask the BA OQ-13 (recognising an adjourned offence; ADJNN never sent) and the developer/BA OQ-14 (same-day reshare giving a never-sent offence its final result); follow the OQ-9 discussion (guaranteed correlation attribute) with the tech lead and architect; record the answers in `specs/002-enforcement-nows-generation/research.md` §4 and adjust R18/R19, T020/T021/T035/T046/T051 if they change.

---

## Phase 2: Foundational (blocks all user stories)

**Purpose**: CIMD-4462 data model in H (R13), statuses, kept-data table and enforcement columns in H, the share correlation id in W and H (R18), W's hand-over columns, correlation key and sent-offence table (R19-R21), and the W↔H contract copy (constitution II v1.1.1).

### Tests first

- [X] T007 [P] Port PR #186's schema test into H: `Hcmdt/schema/FinancialOrderDetailsSchemaTest.java` and `Hcmd/src/test/resources/financial-order-details-with-enf-nows-data-items.json` (from `refs/pr/186`); it fails until T012.
- [X] T008 [P] Write `../cpp-context-hearing-nows/hearingnows-persistence/hearingnows-persistence-api/src/test/java/uk/gov/moj/cpp/hearing/nows/persistence/repository/EnforcementNowsDataRepositoryTest.java` (insert, find by `correlationId`+`masterDefendantId`, duplicate key rejected, two correlation ids (two hearing days) for one hearing and defendant coexist), following H's existing repository test setup; if H has no repository test harness, cover these cases in `Hit/it/EnforcementNowsIT.java` (T028) and note it here.
- [X] T009 [P] Extend `Wt/repository/HearingResultSubmissionRepositoryIntegrationTest.java`: (a) `nows_handover_status`, `nows_handover_detail`, `nows_handover_at` round-trip and default to null; (b) two rows with the same hearing, case and defendant but different `correlation_id` are allowed, the same `correlation_id` twice is rejected; (c) `gob_sent_offence`: the same `(case_id, offence_id)` twice rejected, the same `(case_id, judicial_result_id)` twice rejected, a row with both or neither of `offence_id` / `judicial_result_id` rejected (data-model §1, §1b).
- [X] T010 [P] `Wt/service/ShareCorrelationIdTest.java`: the contract's test vectors (`contracts/hearingnows-add-enforcement-nows-data.md`): `forShare(hearingId, hearingDay)` gives the listed ids; different hearing days give different ids (R18). (`UUID.toString()` is always lower-case in Java, so no separate upper-case case is needed.)
- [X] T011 [P] `Hdomt/ShareCorrelationIdTest.java`: the same test vectors as T010 (R18).

### Implementation

- [X] T012 Bring PR #186 into H (R13): copy `Hcmd/src/raml/json/schema/nows-data-items.json`, add `enfNowsDataItems` (`$ref` nows-data-items.json) to `Hcmd/src/raml/json/schema/financial-order-details.json`, copy the POJOs `Hdom/pojo/court/nowdocument/enforcement/*.java`, and update `Hdom/pojo/court/nowdocument/FinancialOrderDetails.java` (field, constructor, builder, equals/hashCode) as in `refs/pr/186`, resolved against current `main`; T007 passes.
- [X] T013 Add statuses `WAITING_FOR_ENFORCEMENT_DATA` and `ENFORCEMENT_DATA_ADDED` to `Hdom/NowDocumentRequestStatus.java` (including `valueFor()`).
- [X] T014 Create `Hlqb/004-create-enforcement-nows-data.xml`: table `enforcement_nows_data` with the columns of data-model.md §3, PK `correlation_id`+`master_defendant_id`, `author="cpp-context-hearing-nows"` (pattern: `003-add-hrds-notification-status.xml`; the changelog uses `includeAll`).
- [X] T015 Create `Hlqb/005-add-enforcement-columns-to-now-document-request.xml` (columns `master_defendant_id uuid`, `enforcement_correlation_id uuid`, index on `enforcement_correlation_id, master_defendant_id, status`); add both fields to `Hper/entity/NowDocumentRequestEntity.java`; set `master_defendant_id` from the request in `Hcmdj/service/AddNowDocumentRequestService.java`.
- [X] T016 Create `Hper/entity/EnforcementNowsDataEntity.java` (+ composite key class) and `Hper/repository/EnforcementNowsDataRepository.java` (plain JDBC, like `NowDocumentRequestRepository`): find by key, conditional status updates; T008 passes.
- [X] T017 Add to `Hper/repository/NowDocumentRequestRepository.java`: `findByEnforcementCorrelationIdAndMasterDefendantIdAndStatus(...)` and `updateStatusIfCurrent(materialId, from, to, errorMessage)` returning the row count (R5).
- [X] T018 [P] Create W migration `src/main/resources/db/migration/V1.002__add_nows_handover_to_hearing_result_submission.sql` (three nullable columns, data-model.md §1), enum `Wm/entity/NowsHandoverStatus.java` (`HANDED_OVER`, `FAILED`), and the fields in `Wm/entity/HearingResultSubmissionEntity.java`.
- [X] T019 Create W migrations (one change per file, constitution III; split in the 2026-10-12 review) `src/main/resources/db/migration/V1.003__add_share_correlation_to_hearing_result_submission.sql`, `V1.004__add_case_defendant_status_index_to_hearing_result_submission.sql` and `V1.005__create_gob_sent_offence.sql` (R21: `correlation_id` backfilled with `id`, NOT NULL, unique key swapped to `(correlation_id, case_id, defendant_id)`; table `gob_sent_offence` with the partial unique indexes and check constraint of data-model §1b), field `correlationId` in `Wm/entity/HearingResultSubmissionEntity.java`, status `SKIPPED_NOTHING_NEW` in `Wm/entity/SubmissionStatus.java`, `Wm/entity/GobSentOffenceEntity.java` and `Wm/repository/GobSentOffenceRepository.java`; T009 passes.
- [X] T020 [P] Implement `Wm/service/ShareCorrelationId.java` (static `forShare(UUID hearingId, LocalDate hearingDay)`, R18); T010 passes.
- [X] T021 [P] Implement `Hdom/ShareCorrelationId.java` (same rule, Java 17); T011 passes.
- [X] T022 [P] Create the W contract copy (finalised in T030): `src/test/resources/contracts/hearingnows/hearingnows.add-enforcement-nows-data.json` and `src/test/resources/contracts/hearingnows/nows-data-items.json` (byte-identical to H, no header; the sync note lives in `Wt/support/HearingNowsContract.java`: "keep in sync with cpp-context-hearing-nows; do not edit independently"), and `Wt/support/HearingNowsContract.java` validating a payload against it (approach of `Wt/support/LibraContract.java`).

**Checkpoint**: H builds with the CIMD-4462 model, statuses, `enforcement_nows_data` and the new columns; W has the columns, the new key, the sent-offence table, `ShareCorrelationId` and the contract helper; both pass the same correlation-id vectors.

---

## Phase 3: User Story 1 — Enforcement NOWS generated with GOB's data, whichever flow arrives first (P1) 🎯 MVP

**Goal**: W hands GOB's data to H's enforcement-specific command after GOB accepts; H holds Enforcement-case NOWs and completes them with their own share's GOB data in either order; the existing chain generates the document.
**Independent Test**: quickstart scenarios 1, 2, 8, 14.

### Tests first (H)

- [X] T023 [P] [US1] `Hcmdt/AddEnforcementNowsDataCommandApiTest.java`: valid payload → kept data stored under (`correlationId`, `masterDefendantId`) and waiting NOWs for the key completed; no waiting NOW → kept only; `correlationId` not matching R18 for `hearingId` + `hearingDay` → rejected (400); a later `sharedTime` for the same key → data replaced, covered ids accumulated; same or earlier → no-op; userId missing → error.
- [X] T024 [P] [US1] `Hcmdt/accesscontrol/AddEnforcementNowsDataAccessControlTest.java` extending `BaseDroolsAccessControlTest` (`COMMAND_API_SESSION`): "System Users" allowed, others denied (pattern: `cpp-context-results/.../ResultsQueryApiAccessControlTest.java`; add the test-utils dependency to `Hcmd/pom.xml` if missing).
- [X] T025 [P] [US1] Hold-branch tests in `Hcmdt/AddNowDocumentRequestCommandApiTest.java` (the NOW's correlation id comes from a mocked `NowShareCorrelationResolver`, T034): toggle off → existing behaviour; `prosecutorOUCode` = `GAPGD00` and no kept data → `WAITING_FOR_ENFORCEMENT_DATA` with `enforcement_correlation_id` set, no Progression call; with kept data for the same correlation id and master defendant → completion enqueued; kept data only for **another** correlation id of the same hearing → waits; other OU code → existing behaviour; no `prosecutorOUCode` on any case → row `SUBMITTED` and `CHECK_ENFORCEMENT_NOW_TASK` enqueued; Enforcement NOW without `masterDefendantId` → `FAILED` "no master defendant id"; financial NOW waiting for GOB → existing `WAITING_FOR_GOB` path (R3, R4).
- [X] T026 [P] [US1] `Hjobt/tasks/CheckEnforcementNowTaskTest.java` and `Hsvct/EnforcementCaseServiceTest.java`: Progression returns `GAPGD00` → held as in T025; other code → `PROCESS_NOW_DOCUMENT_REQUEST_TASK`; Progression failure → retry via `SetFailedStatusTaskFactory`, never "not enforcement" (analysis I1).
- [X] T027 [P] [US1] `Hjobt/tasks/CompleteEnforcementNowTaskTest.java`: merges `enfNowsDataItems` verbatim and `accountPaymentReference`; creates `financialOrderDetails` when absent with `totalBalance`/`totalAmountImposed` from GOB values when present (R6, OQ-1); chains to `PROCESS_NOW_DOCUMENT_REQUEST_TASK`; losing the conditional update → does nothing.
- [X] T028 [US1] H IT `Hit/it/EnforcementNowsIT.java` (extends `BaseIT`; toggle on via `FeatureStubber`): (a) NOW first then hand-over, (b) hand-over first then NOW → both `DOCUMENT_REQUESTED` with GOB's items in the stored payload; (c) non-enforcement NOW unchanged; (d) NOW without `prosecutorOUCode` → Progression stub (`Hit/stub/ProgressionStub.java` + stub-data with `GAPGD00`) → held; (e) a hand-over with no NOW creates no `now_document_request` row (FR-007); (f) hand-over with a wrong `correlationId` → 400. Add `ENFORCEMENT_NOWS_DATA` to `Hit/stub/ApiRestEndpoint.java` and a payload template under `hearingnows-integration-test/src/test/resources/json/`. (g) NOW whose `orderDate` differs from the hand-over's `hearingDay` → not completed, `FAILED` "no GOB data" after a short JNDI max wait (quickstart 23).

### Implementation (H)

- [X] T029 [US1] Add the Drools rule `Hcmd/src/main/resources/uk/gov/moj/cpp/hearing/nows/command/api/accesscontrol/add-enforcement-nows-data-command-rules.drl` ("System Users" only); T024 passes.
- [X] T030 [US1] Add the command to `Hcmd/src/raml/hearingnows-command-api.raml` (`/enforcement-nows-data`, POST, `application/vnd.hearingnows.add-enforcement-nows-data+json`, name `hearingnows.add-enforcement-nows-data`, 202) with schema `Hcmd/src/raml/json/schema/add-enforcement-nows-data.json` (twelve required fields incl. `correlationId`, `hearingDay`, `sharedTime`, `gobDataReused`, `coveredResultDefinitionIds`; `$ref` `nows-data-items.json`; contracts/hearingnows-add-enforcement-nows-data.md) and an example under `Hcmd/src/raml/json/`; refresh W's copy (T022).
- [X] T031 [US1] Implement `Hsvc/EnforcementCaseService.java`: `isEnforcement(nowContent)` from `cases[].prosecutorOUCode` (JNDI code); `isEnforcementViaProgression(caseIds)` via the existing `Hsvc/ProgressionQueryService.java` (`prosecutionCaseIdentifier.prosecutionAuthorityOUCode`).
- [X] T032 [US1] Implement `Hsvc/EnforcementNowsDataService.java`: `keep(handOver)` (upsert on (`correlationId`, `masterDefendantId`) versioned by `sharedTime`: no row → insert; later → replace data, accumulate covered ids, restart retention; same or earlier → no-op; commit; schedule release on every keep or replace, T066), `findKept(key)`, `completeWaiting(key)` (enqueue `CompleteEnforcementNowTask` for each `WAITING_FOR_ENFORCEMENT_DATA` NOW of the key; mark data `CLAIMED`), "write, then look for the other side" (R5).
- [X] T033 [US1] Implement `Hcmdj/AddEnforcementNowsDataCommandApi.java` (`@ServiceComponent(COMMAND_API)`, `@FeatureControl(FEATURE_ENFORCEMENT_NOWS)`, `@Handles("hearingnows.add-enforcement-nows-data")`, userId required, `correlationId` recomputed with `ShareCorrelationId` and checked) calling `EnforcementNowsDataService`; logs ids/caseUrn/outcome only; T023 passes.
- [X] T034 [US1] Add the hold branch to `Hcmdj/AddNowDocumentRequestCommandApi.java` (payload-first check; `masterDefendantId` missing → `FAILED`; no `prosecutorOUCode` → enqueue `CHECK_ENFORCEMENT_NOW_TASK`; held → set `enforcement_correlation_id` from the interface `Hsvc/NowShareCorrelationResolver.java` (returns the NOW's correlation id), `WAITING_FOR_ENFORCEMENT_DATA`, commit, look up kept data, enqueue completion if present), and implement `Hjob/tasks/CheckEnforcementNowTask.java` (+ `Hjob/jobdata/CheckEnforcementNowJobData.java`, name in `Hjob/tasks/NowDocumentRequestTaskNames.java`, retries via `Hjob/tasks/SetFailedStatusTaskFactory.java`); T025 and T026 pass.
- [X] T035 [US1] Implement `Hsvc/NowShareCorrelationResolver.java` (interim, R18): `ShareCorrelationId.forShare(now.hearingId, nowContent.orderDate)`; unit tests in `Hsvct/NowShareCorrelationResolverTest.java` (contract vectors; `orderDate` used as the hearing day). Keep the class the single place to change when OQ-9 is settled.
- [X] T036 [US1] Implement `Hjob/tasks/CompleteEnforcementNowTask.java` (conditional update, payload merge via a new `Hsvc/EnforcementFinancialOrderDetailsMerger.java`, then `PROCESS_NOW_DOCUMENT_REQUEST_TASK`), job data `Hjob/jobdata/CompleteEnforcementNowJobData.java`, task name; T027 passes (T028 after T035).

### Tests first (W)

- [X] T037 [P] [US1] `Wt/client/HearingNowsClientTest.java` (`MockRestServiceServer`): POST path, media type, `CJSCPPUID`; 202 → `HandedOver`.
- [X] T038 [P] [US1] `Wt/service/NowsHandoverPayloadBuilderTest.java`: `correlationId` = `ShareCorrelationId.forShare(hearing.id, hearingDay)`, `hearingDay`, ids, caseUrn, `accountNumber` = `prosecutorDefendantId`, `sharedTime`, `gobDataReused: false`, `coveredResultDefinitionIds` = result type ids of the results sent in this submission, `nowsDataItems` equal to the raw reply's node (R10); payload valid against `HearingNowsContract` (T022).
- [X] T039 [P] [US1] `Wt/service/HearingResultedProcessorTest.java`: the processor computes the correlation id and looks for an existing submission by (`correlationId`, case, defendant); after a parsed SUCCEEDED reply the client is called once and `recordHandover(HANDED_OVER)`; not called for an unparsable 2xx, FAILED, MAPPING_FAILED, SKIPPED or NOT_SENT (FR-001, FR-012).
- [X] T040 [US1] W ITs: H WireMock stub (202) in `Wt/integration/WorkflowStubsIntegrationTestBase.java` (`cp.hearing-nows.base-url` → stub); `Wt/integration/HearingResultedFlowIntegrationTest.java` asserts one hand-over POST valid against the contract, carrying the expected `correlationId`, and `nows_handover_status = HANDED_OVER`; extend `Wt/integration/ScenarioIntegrationTest.java` with an optional `expected.handover` (posts count, status, `gobDataReused`) and set it in the existing scenario folders under `src/test/resources/scenarios/`.

### Implementation (W)

- [X] T041 [US1] Add `hearingNowsRestClientBuilder` (connect 5s / read 10s from `cp.hearing-nows.*`) to `Wm/config/RestClientConfig.java` and a timeout case in `Wt/config/RestClientConfigTest.java`.
- [X] T042 [US1] Implement `Wm/client/HandoverResult.java` and `Wm/client/HearingNowsClient.java` (never throws; 202 → `HandedOver`; R9); T037 passes.
- [X] T043 [US1] Implement `Wm/service/NowsHandoverPayloadBuilder.java` (raw reply → `nowsDataItems` node unchanged; `correlationId`; covered ids; R10); T038 passes.
- [X] T044 [US1] In `Wm/service/HearingResultedProcessor.java` and `Wm/service/SubmissionStore.java`: compute the correlation id (event `hearingDay` missing → skip with new `SkipReason.HEARING_DAY_MISSING`, WARN, no row), use (`correlationId`, case, defendant) in `findExisting`, `recordSending` and the other `record*` methods (store `correlation_id`), call the hand-over after `recordSucceeded` for a parsed reply, and add `recordHandover(id, status, detail)`; update `Wt/service/SubmissionStoreIntegrationTest.java` for the new key; T039 and T040 pass.

**Checkpoint**: MVP: both orders produce a document with GOB's data; W records `HANDED_OVER`.

---

## Phase 4: User Story 6 — Each offence is sent to GOB once; later shares send only what is new (P1)

**Goal**: a later share of the same hearing (adjourned offences resulted later) is a new submission with only the offences not yet sent; an offence already sent is never re-sent, whatever the payload repeats; nothing new → no GOB call (FR-018 to FR-021, R18-R21).
**Independent Test**: quickstart scenarios 15-18.

### Tests first (W)

- [X] T045 [P] [US6] Extend `Wt/service/ResultCodeResolverTest.java`: each resolved result keeps its `offenceId` (offence level) or its `judicialResultId` (defendant-case / hearing level), its `judicialResultTypeId`, its CP shortCode, whether it is an adjournment (`nextHearing` present or `isAdjournmentResult: true`), and its GOB code or drop reason; the existing aggregate behaviour (codes, dropped codes) is unchanged. Add `isAdjournmentResult` and `nextHearing` (presence only) to `JudicialResult` in `Wm/event/HearingResultedEvent.java` with a deserialisation test.
- [X] T046 [P] [US6] `Wt/service/OffenceResultFilterTest.java`: offences in `gob_sent_offence` for the case are dropped with all their results; an offence adjourned in this share (a result with `nextHearing`, `isAdjournmentResult: true`, or a shortCode in `cp.hearing-result.adjournment-result-codes`, env `ADJOURNMENT_RESULT_CODES`, default NEXH, ADJNN) is dropped and not recorded, even when another of its results has a GOB code; an offence whose earlier results had no GOB code is not in the table and is kept; non-offence results dropped by `judicialResultId` (R20); output lists which offences / result ids will be recorded as sent (only those with at least one GOB code).
- [X] T047 [P] [US6] Extend `Wt/service/HearingResultedProcessorTest.java`: (a) share 2 of the same hearing (another `hearingDay`, `isReshare: false`) after share 1 → new submission with share 2's new offences only; (b) everything already sent or adjourned → `SKIPPED_NOTHING_NEW`, no gateway call, hand-over of the most recent GOB data for the case and defendant with `gobDataReused: true` and covered ids (none → no hand-over); (c) the same share again (same hearing day) → "already submitted", nothing sent; (e) `hearingDay` missing → skipped with reason `HEARING_DAY_MISSING` (WARN, no row: no correlation id can be built); (d) a different hearing id repeating an already-sent offence → that offence not sent (by design the filter is keyed by case and offence only, never by hearing; covered by `OffenceResultFilterTest.offence_already_sent_should_be_dropped_with_all_its_results_even_when_repeated`). Review 2026-10-12: `sharedTime` missing → `SHARED_TIME_MISSING`; a share with no GOB-recognised code records `SKIPPED_NO_RESULT_CODE` and gets the reuse hand-over (FR-020).
- [X] T048 [US6] Extend `Wt/service/SubmissionStoreIntegrationTest.java`: `recordSending` inserts the `SENDING` row and the sent-offence rows in one transaction; when a concurrent share has already recorded one of the offences, the insert fails as a whole (no `SENDING` row, nothing sent) and returns "offences already sent" so the processor re-filters; `recordSkippedNothingNew` writes `SKIPPED_NOTHING_NEW`.
- [X] T049 [US6] Scenario folders under `src/test/resources/scenarios/` (event fixtures under `src/test/resources/events/`, published in order with `events`): `33-later-share-sends-only-new-offence/` (quickstart 15: share 1 offence 1 resulted + offence 2 adjourned; share 2 offence 2 resulted with offence 1 repeated → 2 GOB POSTs with the expected codes each), `34-later-share-nothing-new-reuses-gob-data/` (16: no GOB POST, one reuse hand-over), `35-later-share-redelivered/` (17), `36-three-offences-three-shares/` (18), `37-adjourned-offence-sent-on-final-result/` (20), `38-defendant-level-result-sent-once/` (21). Extend `Wt/integration/ScenarioIntegrationTest.java` so a scenario can expect several GOB requests in order (`resultCodesPerPost`, and `expected.statuses` for several rows). Review 2026-10-12: hand-over expectations `hearingDayPerPost`, `sharedTimePerPost`, `detailsPerRow`; every hand-over's `correlationId` is checked against its hearing id and day; every `SUCCEEDED` row must have a recorded hand-over; new `39-later-share-no-gob-code-reuses-gob-data/` (FR-020).

### Implementation (W)

- [X] T050 [US6] Change `Wm/service/ResultCodeResolver.java` and `Wm/service/ResolvedCodes.java` to keep the per-result attribution of T045 (e.g. a list of `ResolvedResult(offenceId, judicialResultId, judicialResultTypeId, gobCode)` alongside the existing aggregates); T045 passes.
- [X] T051 [US6] Implement `Wm/service/OffenceResultFilter.java` using `Wm/repository/GobSentOffenceRepository.java` (find by case and offence ids / result ids) and the adjournment rule (property `cp.hearing-result.adjournment-result-codes`, env `ADJOURNMENT_RESULT_CODES`, in `Wm/config/HearingResultProperties.java` and `application.yaml`, default `NEXH,ADJNN`); T046 passes.
- [X] T052 [US6] Extend `Wm/service/SubmissionStore.java`: `recordSending(..., sentItems)` as one `@Transactional` method with the sent-offence rows (unique-key violation on `gob_sent_offence` → nothing recorded, "already sent" result; violation on the submission key → "already recorded" as today), and `recordSkippedNothingNew(...)`; T048 passes.
- [X] T053 [US6] Wire it in `Wm/service/HearingResultedProcessor.java`: resolve → filter → (nothing left → `SKIPPED_NOTHING_NEW`, log counts only, then a reuse hand-over via a new `Wm/service/GobDataReuseHandover.java`: most recent `SUCCEEDED` submission with a parsable reply for the case and defendant via a new `SubmissionStore.findSucceeded(caseId, defendantId)`, covered ids = share results whose GOB code was in any of those requests, payload with `gobDataReused: true`, recorded on the new row; none → no hand-over) → map with the remaining codes and NOWS items → `recordSending` with the sent items (re-filter once on "already sent") → send; `coveredResultDefinitionIds` = the type ids actually sent; T047 and T049 pass.
- [X] T054 [US6] H IT case in `Hit/it/EnforcementNowsIT.java`: two hearing days of one hearing and defendant, each handed over with its own correlation id; the NOWs of each day (`orderDate` = that day) are completed only with their own day's GOB data, in either order (spec US6 scenario 5).
- [X] T055 [US6] Update feature 001 docs for the changed behaviour: `specs/001-cimd-4246-hearing-resulted-to-libra/spec.md` (repeated delivery = same share; a later share of the same hearing is sent with new offences only; each offence once), `research.md` (R17 key now `correlation_id`; link to 002 R18-R21) and `data-model.md` (unique key, `SKIPPED_NOTHING_NEW`, `gob_sent_offence`); `README.md` runbook: statuses and a query for a case's sent offences.

---

## Phase 5: User Story 2 — The hand-over is recorded and never re-sends to GOB (P2)

**Goal**: every hand-over outcome recorded; failures never resend to GOB or stop the listener.
**Independent Test**: quickstart scenario 9.

- [X] T056 [P] [US2] Extend `Wt/client/HearingNowsClientTest.java`: 400/403/500 → `Failed("HTTP <status>")`; connection refused / read timeout → `Failed(<cause class>)`; never throws.
- [X] T057 [P] [US2] Extend `Wt/service/HearingResultedProcessorTest.java`: a failed hand-over records `FAILED` + detail, the submission stays `SUCCEEDED`, the gateway client is called once (no GOB resend), the next event is processed (`NowsHandoverFailureIntegrationTest.next_share_should_be_processed_after_a_failed_hand_over`, added in the 2026-10-12 review).
- [X] T058 [US2] W IT `Wt/integration/NowsHandoverFailureIntegrationTest.java`: H stub 403 / 500 / delay beyond read timeout → `nows_handover_status = FAILED` with detail; one gateway POST; logs contain no GOB data/account number/PII.
- [X] T059 [US2] Implement the failure mapping in `Wm/client/HearingNowsClient.java` and the WARN log in `Wm/service/HearingResultedProcessor.java` (ids, caseUrn, detail only); T056-T058 pass.
- [X] T060 [P] [US2] Add scenario folder `src/test/resources/scenarios/29-nows-handover-failed-recorded/` (H stub 500 → `FAILED`, GOB called once).

---

## Phase 6: User Story 3 — Each NOW receives the right data, exactly once (P2)

**Goal**: duplicates ignored, interleaved shares/hearings/defendants isolated, simultaneous arrival completes once.
**Independent Test**: quickstart scenarios 3, 4, 5.

- [X] T061 [P] [US3] `Hsvct/EnforcementNowsDataServiceTest.java`: duplicate hand-over (same correlation id and master defendant) → no-op; several waiting NOWs for one key → all completed; NOWs of other keys (another hearing day of the same hearing, another defendant) untouched; a later `sharedTime` for the same key replaces and accumulates covered ids.
- [X] T062 [P] [US3] Race case in `Hjobt/tasks/CompleteEnforcementNowTaskTest.java` (T027): two completions of one NOW → exactly one conditional update succeeds, one processing task enqueued.
- [X] T063 [US3] Extend `Hit/it/EnforcementNowsIT.java`: two NOWs for one defendant around one hand-over (each completed once); two defendants / two hearings interleaved (no cross-data); same hand-over twice (no second document).
- [X] T064 [US3] Harden `Hsvc/EnforcementNowsDataService.java` and `Hper/repository/NowDocumentRequestRepository.java` (conditional updates, key-scoped queries) until T061-T063 pass.

---

## Phase 7: User Story 4 — Nothing waits forever unnoticed (P3)

**Goal**: waiting NOWs fail "no GOB data" after the maximum wait; all kept data (claimed or not) is released after retention; no hand-over when GOB did not accept or nothing new was sent.
**Independent Test**: quickstart scenarios 6, 7 and 16 (shortened JNDI periods).

- [X] T065 [P] [US4] `Hjobt/tasks/ExpireWaitingEnforcementNowTaskTest.java` (still waiting → `FAILED` "no GOB data"; completed → no-op) and `Hjobt/tasks/ReleaseEnforcementNowsDataTaskTest.java` (`release_at` passed: unclaimed → data nulled, `UNCLAIMED`; claimed → data nulled, `RELEASED`; `release_at` in the future → no-op) (analysis I2, U3).
- [X] T066 [US4] Implement `Hjob/tasks/ExpireWaitingEnforcementNowTask.java` and `Hjob/tasks/ReleaseEnforcementNowsDataTask.java` (+ job data, task names); schedule expiry when a NOW starts waiting (T034) and release on every hand-over, scheduled before the keep (T032; review 2026-10-12) with `withNextTaskStartTime` and the JNDI periods; T065 passes.
- [ ] T067 [US4] (written, never run: both cases are skipped unless `-DenforcementNowsShortPeriods=true` with short JNDI periods in the external cpp-developers-docker `standalone.xml`, and they still use `Thread.sleep` and assert `FAILED` only, not `error_message` or the data status; review 2026-10-12) Extend `Hit/it/EnforcementNowsIT.java` with short JNDI periods: NOW with no hand-over → `FAILED` "no GOB data"; hand-over with no NOW → `UNCLAIMED`, data removed; claimed data → `RELEASED`, data removed; a replace (reshare) restarts retention and the earlier scheduled release is a no-op.
- [X] T068 [P] [US4] W test in `Wt/integration/FailureOutcomesIntegrationTest.java`: gateway 502, MAPPING_FAILED and reference-data NOT_SENT outcomes with no earlier accepted submission → no hand-over POST, `nows_handover_status` null (FR-012); `SKIPPED_NOTHING_NEW` with no earlier accepted submission → no hand-over (FR-020). Review 2026-10-12: as implemented, FR-012 is covered by the scenario runner (scenarios 08–11 and 25–28 expect 0 hand-overs) and the no-earlier-submission case by `GobDataReuseHandoverTest`; `FailureOutcomesIntegrationTest` itself has no hand-over assertions.

---

## Phase 8: User Story 5 — Amend & reshare NOWS use the GOB data already received (P3) (FR-016, FR-017, R16, R17; revised 2026-10-09)

**Goal**: a reshare is never sent to GOB; W hands over the most recent GOB data for the case and defendant under its hearing day's correlation id, with the covered results; H replaces the day's kept data (later `sharedTime`) and completes an amended NOW only for a covered result; otherwise `FAILED` "result not sent to GOB".
**Independent Test**: quickstart scenarios 11, 12, 13.

- [X] T069 [P] [US5] W tests in `Wt/service/EnforcementCaseSelectorTest.java` and `Wt/service/HearingResultedProcessorTest.java`: reshare of an in-scope Enforcement case → selected for re-hand-over (same scope rules), **no GOB call**; with `SUCCEEDED` submissions for the case and defendant (one or several shares) → hand-over with `gobDataReused: true`, the reshare's `sharedTime`, `hearingDay` and `correlationId` (its hearing day's id), the **most recent** submission's `nowsDataItems`, `coveredResultDefinitionIds` = reshare results whose GOB code was in any of those requests; none accepted → no hand-over (reason logged); out-of-scope shapes still skipped; `gob_sent_offence` unchanged by a reshare.
- [X] T070 [P] [US5] H tests in `Hjobt/tasks/CompleteEnforcementNowTaskTest.java`: an **amended** NOW (`amendmentDate` set) → completed if one of its `resultIdentifier`s is in the kept (accumulated) covered ids, else it keeps waiting (and `ExpireWaitingEnforcementNowTask` fails it "result not sent to GOB" at the max wait; review 2026-10-12), whether the kept data is still the first share's or already the reshare's (either arrival order, run-2 I4); a NOW that is not an amendment is not checked (R17). In `Hsvct/EnforcementNowsDataServiceTest.java`: a reshare hand-over with a later `sharedTime` replaces the day's data and adds its covered ids.
- [X] T071 [US5] Implement the W reshare path on top of `GobDataReuseHandover` (T053): `Wm/service/EnforcementCaseSelector.java` (reshare of an Enforcement case is a distinct result, e.g. `ReshareOfEnforcementCase`, not a terminal skip), `Wm/service/HearingResultedProcessor.java` (reshare → resolve the reshare's codes → `GobDataReuseHandover`, recorded on the most recent `SUCCEEDED` row with detail prefix `reshare`); T069 passes.
- [X] T072 [US5] Implement the "same type of result" check in `Hjob/tasks/CompleteEnforcementNowTask.java`; T070 passes.
- [X] T073 [US5] W scenario folders `src/test/resources/scenarios/30-reshare-same-type-hands-over-stored-data/` (events: first share then reshare; GOB POSTs 1; hand-over POSTs 2, the second `gobDataReused: true` with the **same** `correlationId` (same hearing day) and a later `sharedTime`), `31-reshare-after-rejected-first-share-no-handover/`, `32-reshare-different-result-not-covered/` (covered ids exclude the new result); H IT cases in `Hit/it/EnforcementNowsIT.java`: amended NOW of a covered result completed in either order; of an uncovered result `FAILED` "result not sent to GOB", also when it arrives before the reshare hand-over (quickstart 22).
- [X] T074 [US5] Update docs touched by the reshare behaviour: `README.md` runbook note (reshare re-hand-over, detail prefix, "result not sent to GOB") and `specs/001-cimd-4246-hearing-resulted-to-libra/spec.md` reshare edge case (never sent to GOB; the GOB data already received is re-handed to NOWS by feature 002).

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T075 [P] Write ADR `specs/002-enforcement-nows-generation/adrs/001-enforcement-nows-gob-data-at-rest-in-hearing-nows.md` (R14, FR-015): data kept in H (`enforcement_nows_data`, NOW payload), plain at rest in iteration 1, retention/release rules (claimed and unclaimed, R7), no PII in logs, owners.
- [X] T076 [P] Update `README.md` (W): configuration rows (`HEARING_NOWS_URL`, `HEARING_NOWS_CJSCPPUID`), the hand-over in "Responsibilities", the support query from `contracts/workflow-nows-handover.md`, the meaning of `nows_handover_status` and `correlation_id`, and the rollout rule (H toggle switched on with W's configuration; spec Assumptions).
- [X] T077 [P] Update feature 001 for the write-only decision (R15): `specs/001-cimd-4246-hearing-resulted-to-libra/spec.md` FR-012 and SC-005 ("stored as an audit/error-handling record; GOB's reply is handed to the NOWS service by feature 002"), `data-model.md` (response_payload purpose), and replace `specs/001-cimd-4246-hearing-resulted-to-libra/docs/` links with `specs/docs/` in `tasks.md`, `plan.md`, `research.md`, `spec.md`, `contracts/apim-libra-hearingresulted.md`, `checklists/requirements.md`.
- [X] T078 [P] H log tests (constitution IV, analysis G2): unit tests with a log-capturing appender for `AddEnforcementNowsDataCommandApi`, `EnforcementNowsDataService`, `CompleteEnforcementNowTask` and `CheckEnforcementNowTask`, asserting no `nowsDataItems` values, account number or defendant PII; confirm W's scenario runner `NEVER_LOGGED` covers the hand-over and nothing-new paths.
- [X] T079 Run W `./gradlew clean build pmdMain :enforcement-workflow-simulator:pmdMain` and H `mvn clean install` + `./runIntegrationTests.sh`; fix every failure and PMD/Sonar violation in new code.
- [X] T080 Walk through `specs/002-enforcement-nows-generation/quickstart.md` scenarios 1-21 (automated tests or local run), recording timings for SC-008, and add a "Last validated" line.
- [X] T081 Update `specs/002-enforcement-nows-generation/research.md` §4 with any answers received (OQ-1, OQ-2, OQ-7, OQ-8, OQ-13) and adjust R16, R17, R19, R20 and the affected phases if needed.

---

## Review 2026-10-12 (cross-repo review of W `0dfec36` and H `46c5a24`; uncommitted fixes)

Fixed in W:
- [X] R-W1 Adjournment (FR-018): an offence adjourned on an earlier day and not resulted since was sent on a later share (its repeated adjournment did not count). Now adjourned when an adjournment is among its latest-ordered results (`OffenceResultFilter`, research R19); tests for "still adjourned" and "adjourned again".
- [X] R-W2 FR-020: a later share with no GOB-recognised result (`SKIPPED_NO_RESULT_CODE`) now gets the reuse hand-over; scenario `39-later-share-no-gob-code-reuses-gob-data`. The scenario runner's completion marker uses its own case and defendant ids.
- [X] R-W3 `sharedTime` missing → `SHARED_TIME_MISSING` (share and reshare), instead of a failed insert or an NPE in the hand-over.
- [X] R-W4 Sent-offence rows inserted in a fixed order (no deadlock between concurrent shares).
- [X] R-W5 Startup WARN when `HEARING_NOWS_CJSCPPUID` is the placeholder; docker-compose `HEARING_NOWS_URL` and a WireMock hand-over stub.
- [X] R-W6 Scenario runner: every hand-over's `correlationId` checked against its hearing id and day; `hearingDayPerPost`, `sharedTimePerPost`, `detailsPerRow`; every `SUCCEEDED` row must have a recorded hand-over; reshare fixture `sharedTime` later than the first share (16:00).
- [X] R-W7 `NowsHandoverFailureIntegrationTest`: the next share is processed after a failed hand-over (T057).
- [X] R-W8 Test `PostgresInitialise` creates `enforcementworkflowdb` when missing (shared Postgres on 5432).

Fixed in H:
- [X] R-H1 FR-017 either order: an amended NOW not covered by the kept data keeps waiting (a later reshare hand-over re-triggers completion) and fails "result not sent to GOB" at the maximum wait; IT `amendedNowShouldWaitForAReshareHandOverThatCoversItsResult`.
- [X] R-H2 Public event `public.hearingnows.now-document-requested` leaves out `enfNowsDataItems` (cpp-context-subscriptions' schema rejects it, and GOB data stays off the public topic); the stored payload keeps it. Developer decision 2026-10-12.
- [X] R-H3 OQ-1 answered (developer, 2026-10-12): no `financialOrderDetails` and GOB without `accountBalance` / `offences.accountTotal` → `FAILED` "GOB data missing totals".
- [X] R-H4 Each completed NOW continues with its own message id and user.
- [X] R-H5 Progression: an empty answer or a case without `prosecutionAuthorityOUCode` is retried (never "not enforcement"); a NOW mixing coded and uncoded cases is checked with Progression.
- [X] R-H6 The hold's status change and tasks are one transaction; the release is scheduled before the data is kept.
- [X] R-H7 Covered ids compared case-insensitively; an unparsable `hearingDay` / id answers 400; repository exception messages carry no parameters.
- [X] R-H8 Tests: T025 (toggle off, `WAITING_FOR_GOB` precedence), T028 (e) `handOverWithoutANowShouldCreateNoNowDocumentRequest`, same-share replace on a real DB (`laterHandOverOfTheSameShareShouldReplaceTheKeptData`, also covering T008's coexistence/replace at DB level), T065 (both expiry reasons, completed no-op, in `ExpireAndReleaseEnforcementTasksTest`), T073 (amended/reshare IT), T078 (command API and `CheckEnforcementNowTask` log tests).

Still open after the review (not code defects in either repo):
- [ ] R-O1 Deployment: `HEARING_NOWS_URL` and `HEARING_NOWS_CJSCPPUID` (a confirmed "System Users" member, OQ-5) are not set in `cpp-aks-deploy` for W; the `EnforcementNows` toggle is not primed in `cpp-platform-priming`. Platform team.
- [ ] R-O2 OQ-2: live reference data must confirm the enforcement result definitions are non-financial (a financial one takes H's `WAITING_FOR_GOB` path, never held).
- [ ] R-O3 GOB values outside H's `nows-data-items.json` patterns make H answer 400 (`FAILED`, `HTTP 400`, not retried). W could validate before the hand-over to record a clearer detail.
- [ ] R-O4 T067 short-period ITs (see above).
- [ ] R-O5 T025's "kept data only under another correlation id → waits" has no unit test (covered by the IT `eachHearingDaysNowShouldGetItsOwnGobData`).

## Review 2026-10-13 (final cross-repo review before the PRs; uncommitted fixes)

Fixed in H:
- [X] R2-H1 Progression: a case returned without `prosecutionAuthorityOUCode` (optional in CP; e.g. a prosecutor with no OU code) is not an Enforcement case, so an ordinary NOW is processed as usual. Only a failed lookup or a case Progression does not return is retried (the 2026-10-12 change had made such NOWs retry and then fail).
- [X] R2-H2 Query API (`/nows/hearing/{id}`, `/nows/material/{id}`): the payload leaves out `enfNowsDataItems`, like the public event (developer decision 2026-10-13: GOB account data is not shown to the query API's 12 user groups). Shared helper `EnforcementNowsPayloads` (domain-common). `EnforcementNowsIT` checks GOB data in the stored payload and its absence from the query API.
- [X] R2-H3 Expiry reason: "result not sent to GOB" is decided from the row's covered ids even after the data was released (data handed over before an amended NOW is normally released before the NOW expires).
- [X] R2-H4 Hand-over keep, release and completions are one transaction (`@Transactional keepAndComplete`); a NOW held meanwhile is completed by its +1 minute re-check.
- [X] R2-H5 `CHECK_ENFORCEMENT_NOW_TASK` calls `EnforcementNowsHold.holdInCurrentTransaction` (no interceptor inside the job's transaction), so the jobstore's retry-then-FAILED handling is kept; the command API keeps the transactional `hold`.

Fixed in W:
- [X] R2-W1 Flyway: `V1.003` split into `V1.003` (share correlation key), `V1.004` (reuse-lookup index) and `V1.005` (`gob_sent_offence`), one change per file (constitution III). Not applied in any shared environment (the branch was never deployed); a local database that applied the old `V1.003` must be recreated.
- [X] R2-W2 FR-018 wording: an offence also counts as sent when the send was attempted and failed before reaching GOB (`NOT_SENT`, replayed by CIMD-4259), as the code and data-model always did.
- [X] R2-W3 Tests: reshare without `sharedTime` / `hearingDay`; `NowsHandoverFailureIntegrationTest` reference-data stubs on the results' ordered dates. Docs: quickstart scenario 11 detail, data-model `SKIPPED_NO_RESULT_CODE` reuse, README `bootRun` with `HEARING_NOWS_URL`; synthetic case URN in `specs/docs/CIMD-4246-gap-analysis.md`.

Verified compatible (no change needed): cpp-context-subscriptions (latest main) validates every enforcement NOW event (with or without `financialOrderDetails` before the merge) and every non-enforcement one against its full schema chain; it reads only `hearingId` and `nowContent`. W↔H hand-over, correlation vectors, GOB `nowsDataItems` model, hearing-resulted fields and Progression query re-checked.

Cross-checked against hearing-nows PR #186 (CIMD-4462, the document model for system-doc-generator; not to be merged, this feature's H PR carries it): its model and schema files (`financialOrderDetails.enfNowsDataItems`, the `NowsDataItems` POJO tree, schemas and fixtures) are identical in this branch. New `EnforcementNowDocumentModelTest` (H event processor) sends that PR's fixture through the `NowDocumentContent` POJOs the way `StorePayloadToFileServiceTask` does before the file service, and checks that the GOB data reaches the document generator unchanged.

Still open (outside these repos): `cpp-aks-deploy` values for W (`HEARING_NOWS_URL`, `HEARING_NOWS_CJSCPPUID` and the pre-existing `REFERENCEDATA_URL`, `REFERENCEDATA_CJSCPPUID`, `ENFORCEMENT_GATEWAY_URL`); `EnforcementNows` toggle and optional JNDI values in priming; H's PR targets `team/CCT-1222` (branch based on it).

## Dependencies & Execution Order

- **Setup (Phase 1)** → **Foundational (Phase 2)**: T012 before any H story task; T018-T020 and T022 before W story tasks. T005 and T006 run in parallel; their answers may adjust T020, T021, T035, T046, T051 but block nothing.
- **US1 (Phase 3)**: depends on Phase 2. H (T023-T036) and W (T037-T044) tracks can run in parallel; T030 refreshes W's contract copy (T022) before T038/T040 finalise.
- **US6 (Phase 4)**: W part depends on T044 (correlation key in the processor and store) and T043 (payload builder); independent of the H track except T054 (needs T035).
- **US2 (Phase 5)**: depends on T042, T044.
- **US3 (Phase 6)**: depends on T032, T036 (T063 also on T035).
- **US4 (Phase 7)**: depends on T032, T034; T068 depends on T044 and T053.
- **US5 (Phase 8)**: depends on US1 and US6's W changes (several submissions per case and defendant); independent of US2-US4.
- **Polish (Phase 9)**: after the stories it documents; T077 any time; T075 before any environment beyond local holds GOB data in H.

## Parallel Examples

```text
# Phase 2 tests together
T007 (H schema)   T008 (H repository)   T009 (W migrations)   T010 (W correlation id)   T011 (H correlation id)

# US1: H and W tracks in parallel
H: T023 T024 T025 T026 T027 (tests) → T029-T036
W: T037 T038 T039 (tests) → T041-T044

# US6 tests together
T045 (resolver)   T046 (filter)   T047 (processor)
```

## Implementation Strategy

1. **MVP = Phase 1 + Phase 2 + US1 + US6**: each offence sent once (final results only), later hearing days sent, hand-over with the hearing-day correlation id, H holds and completes NOWs in either order. Deployable behind H's `EnforcementNows` toggle (off by default) and W's configuration. US6's W part fixes feature 001's loss of later hearing days and can ship first.
2. Then US2 (W resilience/recording) and US3 (exactly-once hardening), then US4 (expiry/release).
3. US5 (amend & reshare) after US1 and US6.
4. Polish: the ADR (T075) before any environment beyond local holds GOB data in H (FR-015).
