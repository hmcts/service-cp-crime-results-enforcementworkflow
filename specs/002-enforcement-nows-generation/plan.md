# Implementation Plan: Enforcement NOWS Generation with GOB Account Data

**Branch**: not created (suggested `dev/CIMD-4462-enforcement-nows`) | **Date**: 2026-10-07 (updated 2026-10-08: amend & reshare decided (FR-016/FR-017); analysis fixes I1-I3, C1, U1-U3, G1, G2; updated 2026-10-09: share correlation id, later shares, each offence sent once (US6, FR-018 to FR-021, R18-R21)) | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/002-enforcement-nows-generation/spec.md` (revised 2026-10-07)

## Summary

When a hearing is resulted, two flows run in parallel: the enforcement workflow (**W**) sends an Enforcement case's result to GOB and receives GOB's account data (feature 001), and Common Platform's existing NOWS flow (**DF**, PaaS NOWS functions, unchanged) decides from reference data whether NOWS are needed and sends them to the hearing NOWS service (**H**, `cpp-context-hearing-nows`). The flows meet in H:

- **W** hands GOB's data (`nowsDataItems` verbatim, account number, hearing/case/defendant/master-defendant ids, caseUrn, hearing day, share time and share correlation id) to H synchronously after GOB accepts, through a new enforcement-specific H command, and records the hand-over outcome on its write-only submission record.
- **W** sends each offence's results to GOB at most once, and only when finally resulted (adjourned offences are not sent): a later hearing day's share of the same hearing is a new submission containing only the offences not yet sent; the idempotency key becomes the share correlation id (`UUID` from hearing id + **hearing day**, R18; interim, to be revisited, OQ-9), computed the same way in W and H (H takes the hearing day from the NOW's `orderDate`).
- **H** recognises an Enforcement case's NOW (case's prosecuting authority, via Progression), holds it until GOB's data is available, keeps a hand-over that arrives first, completes each NOW exactly once by writing GOB's data into `financialOrderDetails.enfNowsDataItems` (the CIMD-4462 / PR #186 data model) and the account number, then runs the existing generate → store → distribute chain. Delayed jobstore tasks expire NOWs that never get data ("no GOB data") and release data no NOW claims.

## Technical Context

**Language/Version**: W: Java 25, Spring Boot 4.1, Gradle 9. H: Java 17, CPP framework (`uk.gov.justice.services`, WildFly / Jakarta EE, CDI), Maven.
**Primary Dependencies**: W: Spring Web `RestClient`, Spring Data JPA, Flyway, Jackson 3, Artemis JMS (unchanged). H: RAML command API + JSON schema, Drools access control, JDBC repositories (`EnforcementNowsDataRepository` uses plain JDBC like H's `NowDocumentRequestRepository`), framework jobstore (`@Task`, `ExecutionInfo`), feature control, generated REST clients (Progression already present).
**Storage**: W: PostgreSQL `hearing_result_submission` (+3 hand-over columns, Flyway `V1.002`; + `correlation_id` and a new unique key, index for the reuse lookup, new table `gob_sent_offence`, Flyway `V1.003`–`V1.005`). H: viewstore PostgreSQL, new table `enforcement_nows_data` (Liquibase `004-*`), new `now_document_request` statuses (status is a varchar) and two new nullable columns `master_defendant_id`, `enforcement_correlation_id` with an index on (`enforcement_correlation_id`, `master_defendant_id`, `status`) (Liquibase `005-*`).
**Testing**: W: JUnit 5, Mockito, `MockRestServiceServer`, WireMock, real local Postgres (`PostgresInitialise`), embedded Artemis scenario runner; JSON-schema validation of the hand-over against H's schema (copy). H: JUnit/Mockito unit tests, Drools access-control test (`BaseDroolsAccessControlTest`), schema tests (from PR #186), integration tests (`hearingnows-integration-test`, `BaseIT`, WireMock, `./runIntegrationTests.sh`).
**Target Platform**: W: container on the CP AKS platform (single replica, feature 001). H: WildFly WAR on CPP.
**Project Type**: Two services in two repositories (W Spring Boot microservice; H CPP context).
**Performance Goals**: SC-008 — an enforcement NOW generated within 10 minutes of the later of the two flows reaching H under normal load (the existing chain dominates).
**Constraints**: W→H connect 5s / read 10s, outside the GOB call budget (feature 001 R20 unchanged). No PII or GOB data in logs (W and H). DF unchanged. Non-Enforcement NOWS unchanged in behaviour (SC-007); the enforcement check runs only when H's `EnforcementNows` toggle is on.
**Scale/Scope**: Enforcement cases only (GAPGD00 first shares); ~17 enforcement NOW definitions (CCT-1222); a hand-over per accepted submission.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* Evaluated against constitution **v1.1.1**.

| Principle | Check | Status |
|---|---|---|
| I. Business logic in the workflow, thin gateway | W decides when to hand over (only after GOB accepted); H only holds/completes/generates NOWS. Gateway (G) untouched. | ✅ |
| II. Compile-time contracts | Gateway contract unchanged. H's command schema is H-owned (RAML/JSON schema, H's convention); W validates its payload against a verbatim, sync-noted copy (research R12), as v1.1.1 states for `cpp-context-hearing-nows`. | ✅ |
| III. Persistence by convention | W: new Flyway migration, no edits to applied ones. H: new Liquibase changeset in H's convention (v1.1.0 allows). Exactly-once enforced by a unique key + status-conditional updates (R5). | ✅ |
| IV. Personal data protection | ADR for GOB data at rest in H before production (R14); log assertions in W and H tests. | ✅ (ADR task) |
| V. Bounded, resilient processing | W→H 5s/10s; failure recorded, never resent to GOB, listener continues; every H wait bounded by delayed expiry tasks. | ✅ |
| VI. Pass-through identifiers immutable | caseUrn, account number, ids passed unchanged; GOB data embedded verbatim (R10). | ✅ |
| VII. Tested and clean by default | Tests first in both repos; W ITs with Postgres + WireMock (H stub); H unit, Drools and ITs with its tooling; W `build` + PMD; H `mvn clean install` + its ITs. | ✅ |
| VIII. Security boundary | H command: "System Users" only + feature toggle; W's CJSCPPUID from env/secret. | ✅ |
| IX. Iterative delivery with explicit assumptions | Phases below are independently testable; open points OQ-1..14 recorded with safe defaults; the correlation attribute (hearing day) is an explicit interim decision isolated in `ShareCorrelationId` and H's resolver (OQ-9). | ✅ |
| Architecture constraints | Only `cpp-context-hearing-nows` among `cpp-context-*` is changed, under its conventions and team review (v1.1.0). DF (`cpp-context-azure-legalaidagency`) not changed. | ✅ |

**Result**: PASS (pre-research and post-design). No Complexity Tracking entries.

## Project Structure

### Documentation (this feature)

```text
specs/002-enforcement-nows-generation/
├── spec.md              # revised 2026-10-07
├── plan.md              # this file
├── research.md          # Phase 0 (findings F1-F16, decisions R1-R21, open points OQ-1..12)
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── hearingnows-add-enforcement-nows-data.md   # new H command (W → H)
│   ├── hearingnows-enforcement-now-handling.md    # H behaviour on add-now-document-request for Enforcement cases
│   └── workflow-nows-handover.md                  # W client behaviour and recorded outcomes
├── adrs/                # ADR 001 (GOB data at rest in H) — created by a task
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code

```text
# W — service-cp-crime-results-enforcementworkflow
src/main/java/uk/gov/hmcts/cp/
├── client/HearingNowsClient.java           # new: POST enforcement-nows-data; never throws
├── client/HandoverResult.java              # new: HANDED_OVER | FAILED(+detail)
├── config/RestClientConfig.java            # + hearingNowsRestClientBuilder (5s/10s)
├── entity/HearingResultSubmissionEntity.java  # + nowsHandoverStatus/Detail/At
├── entity/NowsHandoverStatus.java          # new enum
├── service/HearingResultedProcessor.java   # after recordSucceeded → hand over → record
├── service/NowsHandoverPayloadBuilder.java # new: ids + correlationId + accountNumber + nowsDataItems (verbatim node)
├── service/ShareCorrelationId.java         # new: R18 rule (same test vectors as H)
├── service/OffenceResultFilter.java        # new: drops offences/results already sent (R19, R20)
├── entity/GobSentOffenceEntity.java, repository/GobSentOffenceRepository.java  # new
├── service/ResultCodeResolver.java         # resolves per offence (offence id kept with each code)
└── service/SubmissionStore.java            # + recordHandover(...), correlation-id key, sent offences in the SENDING transaction
src/main/resources/
├── application.yaml                        # + cp.hearing-nows.{base-url,cjscppuid,timeouts}
├── db/migration/V1.002__add_nows_handover_to_hearing_result_submission.sql
└── db/migration/V1.003__add_share_correlation_to_hearing_result_submission.sql, V1.004__add_case_defendant_status_index_to_hearing_result_submission.sql, V1.005__create_gob_sent_offence.sql
src/test/java/uk/gov/hmcts/cp/
├── client/HearingNowsClientTest.java
├── service/NowsHandoverPayloadBuilderTest.java, HearingResultedProcessorTest.java (+cases)
├── support/HearingNowsContract.java        # validates payloads against the H schema copy
└── integration/ (WorkflowStubsIntegrationTestBase + H stub; ScenarioIntegrationTest expectations)
src/test/resources/contracts/hearingnows/hearingnows.add-enforcement-nows-data.json, nows-data-items.json
src/test/resources/scenarios/*/scenario.json  # + expected hand-over

# H — cpp-context-hearing-nows
hearingnows-command-api/
├── src/raml/hearingnows-command-api.raml   # + /enforcement-nows-data
├── src/raml/json/schema/hearingnows.add-enforcement-nows-data.json, nows-data-items.json (PR #186), financial-order-details.json (+enfNowsDataItems)
├── src/main/java/.../command/api/AddEnforcementNowsDataCommandApi.java   # new
├── src/main/java/.../command/api/AddNowDocumentRequestCommandApi.java    # + enforcement hold check
├── src/main/resources/.../accesscontrol/add-enforcement-nows-data-command-rules.drl
└── src/test/java/... (command API tests, access-control test, schema tests)
hearingnows-domain-common/.../nowdocument/enforcement/*.java  # PR #186 POJOs; FinancialOrderDetails + enfNowsDataItems
hearingnows-domain-common/.../NowDocumentRequestStatus.java   # + WAITING_FOR_ENFORCEMENT_DATA, ENFORCEMENT_DATA_ADDED
hearingnows-event-processor/.../jobstore/tasks/
├── CheckEnforcementNowTask.java             # Progression fallback when no prosecutorOUCode (retried)
├── CompleteEnforcementNowTask.java          # same-type check (reshare), merge GOB data, chain to PROCESS_NOW_DOCUMENT_REQUEST_TASK
├── ExpireWaitingEnforcementNowTask.java     # FAILED "no GOB data"
└── ReleaseEnforcementNowsDataTask.java      # UNCLAIMED, data removed
hearingnows-event-processor/.../service/EnforcementCaseService.java   # Progression lookup, OU code from JNDI
hearingnows-domain-common/.../ShareCorrelationId.java                # R18 rule (same test vectors as W)
hearingnows-persistence/hearingnows-persistence-api/.../EnforcementNowsDataEntity.java, EnforcementNowsDataRepository.java
hearingnows-persistence/hearingnows-liquibase/.../004-create-enforcement-nows-data.xml, 005-add-enforcement-columns-to-now-document-request.xml
hearingnows-integration-test/.../EnforcementNowsIT.java (+ stub data)
```

**Structure Decision**: Two existing repositories, each in its own conventions (constitution v1.1.0). W changes are confined to the hearing-resulted processing path; H changes add one command, one table, two statuses and three jobstore tasks, and a guarded branch in the existing `add-now-document-request` handling.

## Design

### Sequence (both orders)

```text
hearing-resulted ─┬─> W: select → correlationId → drop offences already sent → map → GOB → SUCCEEDED ─> POST H enforcement-nows-data (202) ─> record HANDED_OVER
                  └─> Results → DF: decide NOWS → POST H add-now-document (per NOW)

H on add-now-document (Enforcement case, toggle on):
   persist SUBMITTED → set WAITING_FOR_ENFORCEMENT_DATA (commit) → look up kept data (correlationId = UUID(hearingId|orderDate), masterDefendantId)
     ├─ found  → complete (conditional update) → PROCESS_NOW_DOCUMENT_REQUEST_TASK (existing chain)
     └─ absent → schedule EXPIRE_WAITING_ENFORCEMENT_NOW_TASK at now + max wait

H on enforcement-nows-data:
   insert kept data (unique key; duplicate = no-op) (commit) → find WAITING_FOR_ENFORCEMENT_DATA NOWs for the key
     ├─ some → complete each (conditional update) → existing chain; mark data CLAIMED
     └─ none → schedule RELEASE_ENFORCEMENT_NOWS_DATA_TASK at now + retention
```

### Key rules

- **Hold condition** (R3, R4): toggle `EnforcementNows` on; NOW would otherwise be processed immediately; any `nowContent.cases[].prosecutorOUCode` = configured enforcement code (no external call). Only if no case carries `prosecutorOUCode`: `CHECK_ENFORCEMENT_NOW_TASK` (jobstore, retried) asks Progression. An Enforcement-case NOW without `masterDefendantId` → `FAILED` "no master defendant id". Otherwise existing behaviour, unchanged.
- **Completion** (R5, R6): status-conditional update `WAITING_FOR_ENFORCEMENT_DATA → ENFORCEMENT_DATA_ADDED`; payload gets `financialOrderDetails.enfNowsDataItems` + `accountPaymentReference` (creating `financialOrderDetails` with GOB's `accountBalance`/`offences.accountTotal` for the required fields when absent, OQ-1); then the existing `PROCESS_NOW_DOCUMENT_REQUEST_TASK`.
- **Kept data lifecycle**: `KEPT` → `CLAIMED` (at least one NOW completed; data retained until release time so later NOWs for the same defendant still complete) → `RELEASED` (data removed at retention end; recorded `UNCLAIMED` when never claimed). A release task is scheduled on every keep or replace and acts only once `release_at` has passed (R7).
- **Amend & reshare** (R16, R17, FR-016, FR-017; decided 2026-10-08, revised 2026-10-09): never sent to GOB. W, on a reshare of an in-scope Enforcement case, hands over the most recent stored `nowsDataItems` for the case and defendant (`gobDataReused: true`) under its hearing day's correlation id, with `coveredResultDefinitionIds`; H replaces the day's kept data when the `sharedTime` is later (covered ids accumulate) and completes an **amended** NOW only if one of its results is covered, otherwise `FAILED` "result not sent to GOB".
- **Later shares, each offence once** (R18-R21, FR-018 to FR-021): W's submission key is (`correlation_id`, case, defendant); offences (and non-offence results) already in `gob_sent_offence`, and offences adjourned in the share, are dropped before mapping (only final results are sent); the sent ones are recorded with the `SENDING` row in one transaction; nothing left → `SKIPPED_NOTHING_NEW` and the most recent GOB data is handed over again (`gobDataReused: true`), as for a reshare.
- **W hand-over** (R9-R11): only after `recordSucceeded` with a parsable reply; payload built from the raw reply's `nowsDataItems` node; 202 → `HANDED_OVER`; else `FAILED` + detail; never throws; never resends to GOB; no hand-over for FAILED/MAPPING_FAILED/SKIPPED/NOT_SENT outcomes (FR-012).
- **Logging**: W and H log ids, caseUrn, statuses only (FR-014); tests assert absence of GOB data/PII.

## Phases (incremental, each independently testable)

1. **H data model** — bring PR #186's schema, POJOs and schema test into H (R13); new statuses; Liquibase `004`; entity + repository. *Test*: schema tests, repository test.
2. **H command (hand-over arrives first)** — RAML, schema, command API, Drools rule, kept-data store, release task. *Test*: command API unit tests, access-control test, IT: hand-over kept then released.
3. **H hold & complete (NOW arrives first / either order)** — enforcement-case service (Progression), hold branch in `AddNowDocumentRequestCommandApi`, complete task, expiry task, race-safe conditional updates. *Test*: unit tests for both orders and the race, IT: both orders end in `DOCUMENT_REQUESTED`, expiry → FAILED "no GOB data", non-enforcement NOW unchanged.
4. **W hand-over** — Flyway `V1.002`, client, payload builder, processor change, store, config; schema copy + contract test. *Test*: unit tests, ITs with an H WireMock stub (202/403/500/timeout), scenario runner expectations, log assertions.
5. **Amend & reshare (FR-016, FR-017)** — W reshare path (selector scope rules, stored-submission lookup, covered results, re-hand-over with `gobDataReused: true`); H `shared_time` versioning of kept data within a hearing day and the "same type of result" check for amended NOWs. *Test*: W scenarios "first share then reshare" (one GOB call, two hand-overs) and "reshare with a different result"; H IT: amended NOW of a covered result completed in either order, of an uncovered result `FAILED`.
6. **Later shares, each offence once (US6)** — W: `ShareCorrelationId` (test vectors), Flyway `V1.003`–`V1.005`, per-offence resolution, `OffenceResultFilter`, sent-offence records, new key, `SKIPPED_NOTHING_NEW`; H: `ShareCorrelationId`, correlation id on kept data and held NOWs (NOW side from `orderDate`). *Test*: W scenarios 15-18, test vectors in both repos, H IT with two shares of one hearing.
7. **Docs & alignment** — ADR 001, quickstart, W README runbook (hand-over columns, query), feature 001 FR-012/SC-005 wording and `specs/docs/` links (R15), research open points.

## Complexity Tracking

None.
