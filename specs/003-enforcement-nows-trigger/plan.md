# Implementation Plan: Triggering NOWS Generation for Enforcement Hearings

**Branch**: `dev/CIMD-3063` (continued; branches managed by the developer) | **Date**: 2026-10-19 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/003-enforcement-nows-trigger/spec.md` (with Clarifications 2026-10-19, 2026-10-20 and 2026-10-22),
based on the architect's design, latest non-final version of 20/10/2026 (reviewed 2026-10-20). Delivered in iterations;
edge-case clarifications (OP-1…OP-18) are folded in as they are agreed.

## Summary

The results-processing app (**FA**) stays the entry point: it creates the NOW requests in the hearing NOWS service
(**H**) as today, gives every NOW of an Enforcement case's defendant a `requestId`, so H holds it in its existing
`WAITING_FOR_GOB` status, and then notifies the enforcement workflow (**W**) with the hearing, hearing date, whether
the share is an amend & reshare (`isReshare`, added 2026-10-22) and the `{requestId, masterDefendantId}` list through a new internal REST endpoint. W stores the notification and processes it
with `cp-task-manager` in two recoverable stages:

1. **Process**: read the share's results (Redis cache by hearing and date, else the Results query), run the unchanged
   001/002 selection, mapping and per-offence filter, and send a **first-time** share to GOB through the gateway once
   per offence, with every result code (decided 2026-10-22). An amend & reshare is never sent to GOB: it, and a
   first-time share with nothing new, reuses the earlier GOB data; with none, nothing is published and the NOWS stay
   held without a time limit.
2. **Publish**: send GOB's NOWS data items verbatim, the account number and the request ids to H as the new public
   event `public.enforcementworkflow.enforcement-nows-data-ready` on Artemis.

H's new event processor completes the held NOWs by `requestId` (or keeps the data for a NOW still to arrive) and runs
the existing generation chain. W's hearing-resulted listener, H's REST hand-over command, the hearing-day correlation
id, the maximum wait and the amended-NOW coverage rule are removed. Retries: 3 with back-off per stage, then a stored
failure replayed automatically on a long schedule or manually by support; an unknown GOB outcome is never retried
automatically.

## Technical Context

**Language/Version**: W: Java 25, Spring Boot 4.1, Gradle 9. H: Java 17, CPP framework (WildFly/Jakarta EE), Maven.
FA: Node.js, Azure Durable Functions (JavaScript), Jest.
**Primary Dependencies**: W: Spring Web (first REST controller), Spring Data JPA, Flyway, Jackson 3, Spring JMS/Artemis
(now also producing), Spring Data Redis + `azure-identity` (Entra auth), `RestClient`, **`uk.gov.hmcts.cp` cp-task-manager
1.0.11** (feed `hmcts-lib`), the generated API of `api-cp-crime-results-enforcementgateway` (now also defining the notification endpoint, R3). H: framework event processor, JSON
schema, jobstore, Liquibase. FA: `durable-functions`, axios wrapper.
**Storage**: W PostgreSQL: new `enforcement_notification`, `enforcement_notification_now`, `jobs` (Flyway
`V1.006`–`V1.009`); `hearing_result_submission`, `gob_sent_offence` unchanged (notification id in `correlation_id`,
data-model.md). H viewstore: changeset
`006` on `enforcement_nows_data`. FA: none.
**Testing**: W: JUnit 5, Mockito, `MockMvc`/`RestTestClient` controller tests, WireMock (Results, gateway, reference
data), Testcontainers Redis, embedded Artemis (consumer asserting `CPPNAME`, `_metadata`, schema),
real local Postgres, schema validation against H's event schema and the OpenAPI. H: unit tests, `EnforcementNowsIT`
publishing public events. FA: Jest.
**Target Platform**: W container on CP AKS (internal ingress only); H WildFly on CPP; FA Azure Function App.
**Project Type**: three components in three repositories (Spring Boot service, CPP context, Function App) plus the
existing enforcement OpenAPI contract repo.
**Performance Goals**: SC-006 — NOW generated within 10 minutes of the share under normal conditions (task poll 5 s,
GOB call ≤ 60 s, H chain dominates).
**Constraints**: FA→W answered after one insert; timeouts per R17; no PII/GOB data in logs; no access keys (Redis via
Entra); each offence to GOB once; unknown GOB outcome never auto-retried; non-enforcement NOWs unchanged.
**Scale/Scope**: Enforcement cases only; one notification per share with enforcement NOWs; ~17 enforcement NOW
definitions.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* Evaluated against constitution **v1.1.1**, re-evaluated against **v1.2.0** (amended 2026-10-19 for this feature) and **v1.2.1** (2026-10-20, Principle V retry wording).

| Principle | Check | Status |
|---|---|---|
| I. Logic in the workflow, thin gateway | Selection, mapping, once-per-offence and reuse stay in W; gateway code unchanged, but rebuilt on the contract version with the open `resultCode` (D7, T087); H only matches and assembles; FA only identifies enforcement NOWs and notifies (no enforcement decision beyond the case rule shared with W). | Pass |
| II. Compile-time contracts | W's new endpoint is added to the existing enforcement contract repo `api-cp-crime-results-enforcementgateway` (one contract repo for all enforcement scenarios, developer 2026-10-19), W implements its generated interface; H event schema owned by H, verbatim copy + validation test in W (exception rule). | Pass with dependency D1 |
| III. Persistence by convention | Flyway `V1.NNN`, one change per file, unique keys for idempotency (`request_id` PK, (`correlation_id` = notification id, `case_id`, `defendant_id`), `gob_sent_offence`); library schema owned by W migrations (R10). | Pass |
| IV. Personal data | No new PII stored by W beyond feature 001's ADR (results are re-read, not stored; notifications hold ids only). GOB data on the public topic and in the broker store → ADR `specs/001-…/adrs/002-CIMD-4530-gob-data-on-public-event.md` (Proposed; production switch-on blocked until Accepted, task T077). Log assertions per new flow. Temporary, documented exception: TEMP payload diagnostics for QA (research I19), removed before main (T090). | Pass with dependency D4 |
| V. Bounded, resilient processing | Timeouts per R17; a failing notification never blocks others (task per notification); every attempt ends in a recorded state; unknown GOB outcome not retried automatically (R11). | Pass |
| VI. Immutable identifiers | `requestId`, `accountNumber`, `caseUrn` passed through unchanged. | Pass |
| VII. Tested and clean | Tests first; unit + IT per component; IT per new endpoint (CLAUDE.md) and per event; H ITs via `runIntegrationTests.sh`; PMD. | Pass |
| VIII. Security boundary | The endpoint triggers GOB updates: internal ingress only (no APIM, no public route) **and** a `CJSCPPUID` allow-list of system users (R2). Bearer auth deferred, owner tech lead (OP-3). Secrets only from env/secret stores. | Pass (mechanism stated, deferred step owned) |
| IX. Iterative delivery, explicit assumptions | Open points OP-1…OP-18 recorded with safe interims and owners; Redis disabled until Entra auth is ready (safe: Results only). | Pass |
| Architecture constraints | v1.2.0 allows: changes to `cpp-context-azure-legalaidagency` (FA) under its own conventions; the REST trigger replacing the hearing-resulted listener; publishing CP public events with the CPP envelope; `cp-task-manager` with W-owned DDL; the `controller` package. | Pass |
| Dev workflow | Branch `dev/CIMD-3063` (feature 002's branch) instead of a branch per spec: justified deviation, see Complexity Tracking; commits by the developer only. | Pass (justified) |

**Gate result**: passes. The amendment required by R19 is done (constitution 1.2.0, 2026-10-19). Re-checked after
Phase 1: no violation.

## Project Structure

### Documentation (this feature)

```text
specs/003-enforcement-nows-trigger/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── adrs/001-gob-data-on-public-event.md        # pointer; the ADR is specs/001-…/adrs/002-CIMD-4530-gob-data-on-public-event.md (constitution IV)
├── contracts/
│   ├── enforcement-notification-openapi.yml        # copy of the gateway contract 0.2.0 (authoritative)
│   ├── workflow-enforcement-notification-api.md
│   ├── public.enforcementworkflow.enforcement-nows-data-ready.json   # draft; owned by H
│   ├── gob-data-public-event.md
│   ├── results-data-sources.md
│   ├── function-app-changes.md
│   └── hearingnows-changes.md
├── checklists/requirements.md
└── tasks.md                                        # /speckit-tasks
```

### Source Code

```text
# W: service-cp-crime-results-enforcementworkflow (this repo)
src/main/java/uk/gov/hmcts/cp/
├── controller/EnforcementNotificationController.java   # implements the generated API (R1-R3)
├── config/  TaskManagerConfig, RedisConfig (Entra), RestClientConfig (Results builder), ResultsProperties, NotificationProperties
├── client/  ResultsClient, ResultsCacheClient            # new; HearingNowsClient removed
├── messaging/ GobDataEventPublisher, CppEnvelope          # new; HearingResultedEventListener + JmsConfig removed
├── service/ NotificationService, HearingResultsSource, ProcessNotificationTask, PublishGobDataTask,
│            ReplayNotificationTask, NotificationReplaySweeper   # new; HearingResultedProcessor adapted (isReshare from the notification,
│            # notification key); ShareCorrelationId removed
├── entity/  EnforcementNotificationEntity, EnforcementNotificationNowEntity, NotificationStatus
└── repository/ EnforcementNotificationRepository, EnforcementNotificationNowRepository
src/main/resources/db/migration/V1.006 … V1.009
src/test/java/uk/gov/hmcts/cp/ (unit, integration, contract tests); src/test/resources/contracts/hearingnows/ (schema copy)

# H: cpp-context-hearing-nows (branch to be based on dev/CIMD-3063)
hearingnows-event-processor/src/main/java/.../event/processor/EnforcementNowsDataEventProcessor.java
hearingnows-event-processor/src/yaml/{subscriptions-descriptor.yaml, json/schema/public.enforcementworkflow.*.json}
hearingnows-command-api (removals; AddNowDocumentRequestService kept-data check)
hearingnows-persistence/hearingnows-liquibase/.../006-key-enforcement-nows-data-by-request-id.xml
hearingnows-integration-test/.../EnforcementNowsIT.java

# FA: cpp-context-azure-legalaidagency/azure-functions/durable-functions
HearingResultedNowsHandler/index.js (request ids, notification; compliance flow unchanged, R29), CommonUtility/EnforcementCase.js, new activity
NotifyEnforcementWorkflow/ + tests (R28: one NOW path; no other existing activity changed)

# Contract repo (existing): api-cp-crime-results-enforcementgateway — src/main/resources/openapi/openapi-spec.yml (new path + tag)
```

**Structure Decision**: W keeps the template package layout (constitution) and adds `controller`; H and FA follow their
own repository conventions.

## Dependencies and sequencing

| # | Dependency | Needed for | Owner |
|---|---|---|---|
| D1 | Notification endpoint added to `api-cp-crime-results-enforcementgateway` (`openapi-spec.yml`, new tag `enforcement-notification`) and a draft version published | W controller against the generated API | Developer (PR in the contract repo) |
| D2 | Constitution amendment 1.2.0 (R19) — **done 2026-10-19** | `/speckit-tasks` | Developer via `/speckit-constitution` |
| D3 | Entra auth on R's Redis + access policy for W's managed identity | switching the cache on (`cp.results-cache.enabled=true`) | Platform / R owners |
| D4 | ADR `specs/001-…/adrs/002-CIMD-4530-gob-data-on-public-event.md` accepted | production switch-on | Architect / IA |
| D5 | `cpp-aks-deploy` values: W `RESULTS_URL`, `RESULTS_CJSCPPUID`, `NOTIFICATION_ALLOWED_SYSTEM_USERS`, `CP_MESSAGING_PUBLIC_EVENT_TOPIC`, Redis host/port, task-manager settings; remove `HEARING_NOWS_*`; FA app settings (function-app-changes.md) | deployment | Developer / platform |
| D6 | Joint release of W, H, FA per environment, flags switched together (R15) | go-live | Tech lead |
| D7 | Open `HearingResult.resultCode` (enum removed, agreed with GOB/Libra 2026-10-22) and required `isReshare` in `api-cp-crime-results-enforcementgateway`; the **gateway service** (`service-cp-crime-results-enforcementgateway`) rebuilt on that contract version before W sends a code outside the 42-value enum of the contract version it uses today (0.0.0-1d55b54) (its generated model rejects unknown enum values); Libra's endpoint accepting any code | sending every result code (FR-010) | Developer / Libra team |

Suggested iteration order (constitution IX): (1) W notification API + stage 1 with Results only + stage 2 publish, H
event processor (US1, US2) → (2) failures and replay (US3) → (3) FA changes → (4) Redis cache once D3 is ready, support
views (US4).

## Release checklist (before main / before each environment)

1. C 0.2.0 published; W `build.gradle` `0.2.0-local` → fixed X.Y.Z, `validateApiSpecVersions` green (T078).
2. Gateway service on that version before W sends codes outside the old enum: bump its contract version
   (0.0.0-1d55b54), change `HearingResultedControllerTest.unknownResultCodeShouldReturn400` and scenario
   `14-unknown-result-code-rejected` to accept any ≤ 10-character code, add a > 10-character → 400 test (T087, D7);
   Libra accepting any code.
3. Deployment values (D5, T075): W `RESULTS_URL`, `RESULTS_CJSCPPUID`, `NOTIFICATION_ALLOWED_SYSTEM_USERS`,
   `CP_MESSAGING_PUBLIC_EVENT_TOPIC`, `CP_MESSAGING_SYSTEM_USER` (OP-16), `DATASOURCE_MAX_POOL` ≥ 2 × executor +
   headroom (OP-15), `RESULTS_CACHE_ENABLED=false` until D3; remove `HEARING_NOWS_*`; FA
   `ENFORCEMENT_WORKFLOW_API_BASE_URI`, `ENFORCEMENT_AUTHORITY_CODE` (W's `NOTIFICATION_ALLOWED_SYSTEM_USERS` = the FA's
   existing `CJSCPPUID` system user, R2); H retention
   setting (time kept after the first claim, R30); internal-only ingress (T076).
4. No feature flags (R25): W, H and FA released together (D6, R15); deploy FA outside resulting peaks (in-flight Enforcement case orchestrations may need a restart).
5. ADR 002 Accepted before production switch-on (D4, T077).
6. T090: TEMP diagnostics removed in W, H and the gateway (`LibraClient`, `ProsecutionCaseClient`); PII log tests
   re-run unfiltered; never deployed to environments with live data.
7. Unreleased migrations changed in place (W `V1.008` `is_reshare`, H changeset 006): reset any local or test database
   that applied an earlier version (Flyway/Liquibase checksum).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Feature 003 developed on feature 002's branch `dev/CIMD-3063`, not on its own branch (constitution: feature branch per spec) | Feature 002 is not merged into `team/CCT-1222` yet and 003 changes the same code; the developer creates and manages branches (developer, 2026-10-19) | A separate branch now would need 002 merged first or constant rebasing of two unmerged branches |

The two items listed before the amendment (changing the Function App repository; W owning `cp-task-manager`'s DDL)
are covered by constitution 1.2.0.
