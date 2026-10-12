# Quickstart: Enforcement NOWS Generation with GOB Account Data

**Feature**: `002-enforcement-nows-generation` | **Plan**: [plan.md](plan.md)

## Build and test

```bash
# W — service-cp-crime-results-enforcementworkflow (needs Postgres on localhost:5432: docker compose up -d postgres)
./gradlew clean build pmdMain :enforcement-workflow-simulator:pmdMain

# H — cpp-context-hearing-nows (Java 17, Maven)
mvn clean install                         # unit tests, schema tests, access-control test
export CPP_DOCKER_DIR=/path/to/cpp-developers-docker
./runIntegrationTests.sh                  # H integration tests incl. EnforcementNowsIT
```

## Configuration

| Where | Key | Default | Purpose |
|---|---|---|---|
| W | `HEARING_NOWS_URL` (`cp.hearing-nows.base-url`) | `http://localhost:8080` | CP backend host serving `/hearingnows-command-api/...` |
| W | `HEARING_NOWS_CJSCPPUID` | placeholder UUID | System user in "System Users" (OQ-5) |
| H (JNDI) | `hearingnows.enforcement.prosecution.authority.code` | `GAPGD00` | Enforcement prosecuting authority |
| H (JNDI) | `hearingnows.enforcement.nows.max.wait` | `PT24H` | How long a NOW waits for GOB data |
| H (JNDI) | `hearingnows.enforcement.nows.data.retention` | `PT24H` | How long unclaimed GOB data is kept |
| H (feature control) | `EnforcementNows` | off | Enables the new command and the hold |

## Verification scenarios

| # | Scenario | Expected |
|---|---|---|
| 1 | NOW first: DF posts an enforcement NOW, then W hands over | NOW `WAITING_FOR_ENFORCEMENT_DATA` → `ENFORCEMENT_DATA_ADDED` → `DOCUMENT_REQUESTED`; payload `financialOrderDetails.enfNowsDataItems` equals GOB's items; W row `HANDED_OVER` |
| 2 | Hand-over first: W hands over, then DF posts the NOW | Kept data `KEPT` → NOW completed on arrival (no waiting) → `DOCUMENT_REQUESTED`; data `CLAIMED` |
| 3 | Two enforcement NOWs for one defendant, hand-over between them | Both completed with the same data, each once |
| 4 | Same hand-over twice | Second has no effect |
| 5 | Two defendants / two hearings interleaved | Each NOW has only its own data |
| 6 | GOB rejected the result (no hand-over) | NOW waits, then `FAILED` "no GOB data" after the max wait (shorten via JNDI in IT) |
| 7 | Hand-over, no NOW ever arrives | Data released after retention, status `UNCLAIMED` |
| 8 | Non-Enforcement NOW | Processed as today; no Progression enforcement check when the toggle is off |
| 9 | H returns 403/500 or times out to W | W row `FAILED` with detail; GOB not re-sent; next event processed |
| 10 | Logs | No GOB data, account number or defendant PII in W or H logs |
| 11 | Amend & reshare changing a result of the same type, after an accepted first share (FR-016/FR-017) | GOB called once (first share only); W hands over the stored data with `gobDataReused: true` and the covered result ids; the reshare's enforcement NOW is completed with it, in either order; W row detail `reshare: …` |
| 12 | Reshare after a first share GOB did not accept | No hand-over; the reshare's enforcement NOW fails with "no GOB data" after the max wait |
| 13 | Amend & reshare adding a completely different result (FR-017) | GOB not called; that result's enforcement NOW is `FAILED` "result not sent to GOB" (email by the notification story) |
| 14 | Enforcement NOW without `prosecutorOUCode` | `CHECK_ENFORCEMENT_NOW_TASK` asks Progression (retried on failure), then held as usual |
| 15 | Share 1 (hearing day 1) results offence 1 and adjourns offence 2; share 2 (same hearing id, hearing day 2) results offence 2 and repeats offence 1's results (US6) | Two GOB POSTs: first with offence 1's codes only, second with offence 2's codes only; two submission rows with different `correlation_id`; `gob_sent_offence` has offence 1 then offence 2; each share's NOWs completed with its own GOB data |
| 16 | A later share containing only offences already sent | No GOB POST; row `SKIPPED_NOTHING_NEW`; the most recent GOB data handed over with `gobDataReused: true`; NOWs DF issues for that share completed with it (covered results) |
| 17 | Same share delivered twice (same hearing day) | One submission, one GOB POST, one hand-over (same `correlation_id`) |
| 18 | Three offences over three shares in any combination (e.g. 1+3, then 2, then 2 and 3 repeated) | Each offence's codes in exactly one GOB POST |
| 19 | `correlationId` test vectors (contract) in W and H | Both compute the same ids |
| 20 | Share 1 adjourns offence 2 with a GOB-recognised code (e.g. ADJNN) or a next hearing; share 2 gives its final result | Share 1 does not send offence 2; share 2 sends offence 2's final result |
| 21 | Defendant-level result (not attached to an offence) repeated in a later share | Sent once only (first share that contains it) |
| 22 | Same hearing day: first share, then a reshare whose amended NOW arrives before the reshare hand-over | One kept-data row for the day; reshare hand-over replaces it (later `sharedTime`); amended NOW completed if its result type is covered, else `FAILED` "result not sent to GOB", in either order |
| 23 | NOW whose `orderDate` differs from the hearing day | NOW waits and fails "no GOB data" after the max wait (interim correlation limitation, OQ-9) |

### Validation (T080)

**Last validated: 2026-10-09.** W: `./gradlew clean build pmdMain :enforcement-workflow-simulator:pmdMain` (243 tests, 0
failures, PMD clean). H: `mvn clean install` and `./runIntegrationTests.sh` green (23 ITs; `EnforcementNowsIT` 8 run, 2
short-period cases skipped by design). SC-008: in the IT environment an enforcement NOW is completed and reaches
`DOCUMENT_REQUESTED` within seconds of the later of the NOW and the hand-over (the whole `EnforcementNowsIT` runs in
about 30 s), well inside 10 minutes.

| # | Covered by |
|---|---|
| 1, 2 | H `EnforcementNowsIT` (either order); H `CompleteEnforcementNowTaskTest`, `EnforcementNowsDataServiceTest`; W `HearingResultedFlowIntegrationTest`, scenario 01 |
| 3, 4, 5 | H `EnforcementNowsIT` (two NOWs around one hand-over, repeated hand-over, other defendant), `EnforcementNowsDataServiceTest` |
| 6, 7 | H `EnforcementNowsIT` with `-DenforcementNowsShortPeriods=true` (needs short JNDI periods in the IT WildFly), `ExpireAndReleaseEnforcementTasksTest`, `EnforcementNowsDataRepositoryTest` |
| 8 | H `EnforcementNowsIT` (non-enforcement NOW), `AddNowDocumentRequestCommandApiTest` |
| 9 | W `NowsHandoverFailureIntegrationTest`, scenario 29, `HearingNowsClientTest`, `NowsHandoverTest` |
| 10 | W scenario runner (`NEVER_LOGGED`), `NowsHandoverTest`, `NowsHandoverFailureIntegrationTest`; H `EnforcementNowsLogTest` |
| 11, 12, 13 | W scenarios 30, 31, 32 (and 02, 14); H `CompleteEnforcementNowTaskTest` (amended NOW covered / not covered) |
| 14 | H `EnforcementNowsIT` (Progression), `CheckEnforcementNowTaskTest`, `EnforcementCaseServiceTest` |
| 15-18 | W scenarios 33-36, `OffenceResultFilterTest`, `SubmissionStoreIntegrationTest` |
| 19 | W `ShareCorrelationIdTest`, H `ShareCorrelationIdTest` (same vectors) |
| 20, 21 | W scenarios 37, 38, `OffenceResultFilterTest` |
| 22 | H `EnforcementNowsDataServiceTest` (replace, covered ids accumulated), `CompleteEnforcementNowTaskTest`; W scenario 30 |
| 23 | H `EnforcementNowsIT` with short periods (NOW with another order date fails "no GOB data") |

Local end-to-end (optional): run W with `SPRING_PROFILES_ACTIVE=docker` against a local Artemis and a WireMock standing in for H (`docker compose --profile stubs up -d wiremock`, add a stub for `POST /hearingnows-command-api/command/api/rest/hearingnows/enforcement-nows-data` → 202), publish an enforcement hearing-resulted event, and check `nows_handover_status = 'HANDED_OVER'`.
