# Enforcement Workflow (service)

`service-cp-crime-results-enforcementworkflow`

A Common Platform (CP) Spring Boot service that owns **enforcement workflow** — the orchestration
and processing that sits behind CP's enforcement functionality.

## Responsibilities

This service is the home for enforcement workflow logic in CP. It holds the business logic (constitution
Principle I). Outbound calls to GOB (Libra) go through `service-cp-crime-results-enforcementgateway`,
which is a thin pass-through to Azure APIM.

Built so far (**CIMD-4246**, iteration 1; spec, plan and tasks in
[`specs/001-cimd-4246-hearing-resulted-to-libra/`](specs/001-cimd-4246-hearing-resulted-to-libra/spec.md)):

1. ~~Consume `public.events.hearing.hearing-resulted`~~: replaced in feature 003 by the notification from the
   results-processing Function App (see below).
2. **Select** the Enforcement case and defendant (`EnforcementCaseSelector`). Prosecuting authority OU
   code `GAPGD00`. Feature 003: an amend & reshare is never sent to GOB (the notification says whether a share is a reshare; step 10). More than one enforcement case, more than one defendant,
   or a linked application are ignored with a warning.
3. **Resolve** result shortCodes from reference data and translate them to GOB codes
   (`ReferenceDataClient`, `ResultCodeResolver`: TFOUT→TFOOUT, WC→DW, WWDN→WDN). Since feature 003 every code
   is sent, also codes GOB did not use before (agreed with GOB/Libra); only results without a shortCode, or with a code
   over 10 characters, are left out and logged.
4. **Choose** the NOWS data items GOB should return (`NowsDataItemSelector`; mapping in config).
5. **Build** the `HearingResultedRequest` (`HearingResultedRequestMapper`). `caseUrn` and the GoB
   account number are passed through unchanged.
6. **Submit** it through the gateway (`EnforcementGatewayClient`) and **store** the request, GOB's reply
   and the outcome in Postgres (`SubmissionStore`, table `hearing_result_submission`). There are no
   retries (CIMD-4259 will add them).

Feature 002 (**CCT-1222 / CIMD-4462**, enforcement NOWS; spec, plan and tasks in
[`specs/002-enforcement-nows-generation/`](specs/002-enforcement-nows-generation/spec.md)):

7. **Each offence once**: one submission per *share* (correlation id = the notification id since feature 003), and each
   offence's final result (and each result not attached to an offence) is sent to GOB at most once
   (`OffenceResultFilter`, table `gob_sent_offence`). A later hearing day of the same hearing sends only its
   newly resulted offences; an adjourned offence is not sent until its final result (adjournment codes in
   `cp.hearing-result.adjournment-result-codes`, default `NEXH,ADJNN`). A share with nothing new is not sent.
8. **Hand over** GOB's data to `cpp-context-hearing-nows`: a share with nothing new reuses the most recent GOB data
   for the case and defendant (`GobDataReuseHandover`); a failure is never a reason to send to GOB again. Feature 003
   replaces the REST hand-over by a public event (below).

Feature 003 (**CIMD-4530**, triggering NOWS generation for enforcement hearings; spec, plan and tasks in
[`specs/003-enforcement-nows-trigger/`](specs/003-enforcement-nows-trigger/spec.md)):

9. **Notification**: the results-processing Function App creates a share's enforcement NOW requests in hearing-nows
   (held there) and calls `POST /enforcement-notifications` (internal network only; `CJSCPPUID` must be in
   `NOTIFICATION_ALLOWED_SYSTEM_USERS`) with the hearing id, hearing date, whether it is an amend & reshare
   (`isReshare`) and the NOW request ids. The notification
   and its request ids are stored (`enforcement_notification`, `enforcement_notification_now`) and processed
   asynchronously by cp-task-manager jobs (`jobs` table). A repeat with the same request ids is a no-op.
10. **Results**: stage 1 reads the share's results from the Results cache (off by default) or the Results query, and
    runs steps 2-7. A **first-time** share sends only offences not yet sent to GOB; an **amend & reshare** (`isReshare`
    in the notification) is never sent to GOB, even when the first-time result never got there. Both reuse the earlier
    GOB data when there is nothing to send; with none, nothing is published (a reshare is retried while the hearing's
    first share is still in progress) and the NOWS stay held in hearing-nows without a time limit. A first-time share with no result that has a code sends nothing to GOB or hearing-nows.
11. **GOB data event**: stage 2 publishes `public.enforcementworkflow.enforcement-nows-data-ready` (request ids, GOB
    account number and NOWS data items) to `public.event`; hearing-nows completes the held NOWS by request id.
12. **Retries**: each stage is retried (`NOTIFICATION_RETRY_DURATIONS`), then stored as `STORED_FAILURE` for a
    replay (`NOTIFICATION_REPLAY_SCHEDULE`; the replay sweep is still to come, CIMD-4530 iteration 3).

Still to come: full defendant/result detail and the other payload blocks (CIMD-4247…4256; spec US5/US6),
and the Enforcement/Confiscation team emails (notification story).

> Owned by the **cp-case-ingestion-and-material** team. Over time, enforcement logic from the results
> service, the SJP flow and the enforcement function apps is intended to move here (strangler-fig).

Contract consumed: [`api-cp-crime-results-enforcementgateway`](https://github.com/hmcts/api-cp-crime-results-enforcementgateway)
(`POST /hearingResulted`). Created from
[`service-hmcts-crime-springboot-template`](https://github.com/hmcts/service-hmcts-crime-springboot-template).

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `DATASOURCE_URL` / `DATASOURCE_USERNAME` / `DATASOURCE_PASSWORD` | `jdbc:postgresql://localhost:5432/enforcementworkflowdb` / `postgres` / `postgres` | Postgres (Flyway migrates on start) |
| `ARTEMIS_BROKER_URL` / `ARTEMIS_USER` / `ARTEMIS_PASSWORD` | `tcp://localhost:61616` / `admin` / `admin` | CP `public.event` topic |
| `REFERENCEDATA_URL` | `http://localhost:8081` | referencedata context base URL (host only; the client appends `/referencedata-query-api/...`), used for the shortCode lookup |
| `REFERENCEDATA_CJSCPPUID` | placeholder UUID | CJSCPPUID system user for reference data (**to be confirmed**) |
| `ENFORCEMENT_GATEWAY_URL` | `http://localhost:8082` | `service-cp-crime-results-enforcementgateway` base |
| `ENFORCEMENT_AUTHORITY_CODE` | `GAPGD00` | OU code that identifies an Enforcement case |
| `NOTIFICATION_ALLOWED_SYSTEM_USERS` | empty (endpoint closed) | comma-separated CJSCPPUID system users allowed to call `POST /enforcement-notifications` (the Function App's) |
| `NOTIFICATION_RETRY_DURATIONS` / `NOTIFICATION_REPLAY_SCHEDULE` | `10s,60s,300s` / `15m,1h,4h,12h,24h` | back-offs of a notification stage, then the replay schedule of a stored failure |
| `RESULTS_URL` / `RESULTS_CJSCPPUID` | `http://localhost:8080` / placeholder UUID | CP host serving the Results internal hearing-details query, and its System Users CJSCPPUID (**to be confirmed**) |
| `RESULTS_CACHE_ENABLED` / `RESULTS_CACHE_USERNAME` / `AZURE_CLIENT_ID` | `false` / empty / empty | Results Redis cache with Entra ID (managed identity token, no access key); `REDIS_HOST` / `REDIS_PORT` / `REDIS_SSL_ENABLED` |
| `CP_MESSAGING_PUBLIC_EVENT_TOPIC` | `public.event` | topic for the GOB data event; locally `jms.topic.public.event` |
| `CP_MESSAGING_SYSTEM_USER` | placeholder UUID | `_metadata.context.user` of the GOB data event (**to be confirmed**, research OP-16). Released together with hearing-nows and the Function App; no feature flags (research R25) |
| `DATASOURCE_MAX_POOL` / `DATASOURCE_CONNECTION_TIMEOUT_MS` / `JOB_EXECUTOR_POLL_INTERVAL` | `12` / `5000` / `5000` | a running task holds two connections: pool ≥ 2 × executor threads (4) + headroom (research OP-15); a pod claims at most 4 jobs at a time (OP-14) |
| `ADJOURNMENT_RESULT_CODES` | `NEXH,ADJNN` | CP shortCodes that mark an offence as adjourned (not sent to GOB in that share; OQ-13) |
| `SPRING_PROFILES_ACTIVE` | none | `docker` adds the diagnostic `public.event` logger (`PublicEventLoggingListener`); publishing the GOB data event does not depend on it |
| `PAYMENT_DUE_DATE_FALLBACK` | `NONE` | **Leave as `NONE` in deployed environments.** `HEARING_DATE` is a local/test/simulator stand-in only (logs a warning at startup) |
| `STALE_SENDING_THRESHOLD` / `STALE_SENDING_SWEEP_INTERVAL` | `PT5M` / `PT1M` | when an interrupted submission is marked failed |

> ⚠️ TEMP DIAGNOSTICS (CIMD-4530, until T090): logger `uk.gov.hmcts.cp.TempDiagnostics` writes full payloads, including
> personal and GOB data. Test environments with synthetic data only; never preprod/prod. To silence it, set
> `logging.level.uk.gov.hmcts.cp.TempDiagnostics=OFF` (e.g. `SPRING_APPLICATION_JSON`); a `LOGGING_LEVEL_…` env var
> does not work here because env var names are lower-cased and the logger name has capitals.

Timeouts follow a budget where each hop gives up before its caller (research.md R20): reference data
5s + 10s; gateway 5s + 50s (the gateway itself uses 5s + 40s towards APIM, and APIM 35s towards Libra).
The shortCode → NOWS data item mapping lives in `cp.hearing-result.nows-data-items-by-short-code` and
takes GOB's `NowsDataItemName` values; an unknown name stops the service at startup.

## Running locally

See [`quickstart.md`](specs/001-cimd-4246-hearing-resulted-to-libra/quickstart.md) for the full steps, and
[feature 003's quickstart](specs/003-enforcement-nows-trigger/quickstart.md) for the notification flow.

```bash
docker compose up -d postgres                      # or reuse a local Postgres on 5432 with database enforcementworkflowdb
docker compose --profile stubs up -d wiremock      # optional: stubs reference data, the gateway and the Results query on :8089
./gradlew build                                    # unit + integration tests (need Postgres on 5432)
REFERENCEDATA_URL=http://localhost:8089 ENFORCEMENT_GATEWAY_URL=http://localhost:8089 RESULTS_URL=http://localhost:8089 \
CP_MESSAGING_PUBLIC_EVENT_TOPIC=jms.topic.public.event NOTIFICATION_ALLOWED_SYSTEM_USERS=5f1a6c2e-0000-4000-8000-00000000fa01 \
PAYMENT_DUE_DATE_FALLBACK=HEARING_DATE SPRING_PROFILES_ACTIVE=docker ./gradlew bootRun
```

## Support runbook

Every attempt ends in one row of `hearing_result_submission` with a final status. Use **read-only**
queries and never select `request_payload` / `response_payload` into tickets or chat: they hold
defendant PII and bank details (specs/001-cimd-4246-hearing-resulted-to-libra/adrs/001).

| Status | Meaning (spec FR-013) | Action |
|---|---|---|
| `SUCCEEDED` | GOB accepted; its reply is stored | none |
| `FAILED` + `http_status` | gateway/GOB rejected or failed; `error_detail` = `error=…; libraStatus=…; errorCode=…` (`errorCode INVALID_RESPONSE` with `libraStatus=200` means GOB **accepted** it but sent back an invalid reply) | investigate; replay is CIMD-4259 |
| `FAILED`, `error_detail` `NOT_SENT – …` | nothing reached GOB (reference data unavailable, or the gateway unreachable) | fix the cause; safe to replay (CIMD-4259) |
| `FAILED`, `error_detail` `TIMEOUT – …` / `INTERRUPTED – …` / `ERROR – …` (outcome at GOB unknown) | CP stopped waiting, or the service stopped mid-call. **GOB may or may not have applied the update** | check the GoB account before any replay; never resend blindly |
| `MAPPING_FAILED` | could not be built: `CASE_URN_INVALID`, `PROSECUTOR_DEFENDANT_ID_MISSING`, `ADDRESS1_MISSING`, `DATE_OF_HEARING_MISSING`, `COURT_HEARING_LOCATION_INVALID`, `PAYMENT_DUE_DATE_UNAVAILABLE`, `ORGANISATION_DEFENDANT_NOT_SUPPORTED_YET` | fix the source data in CP; a repeated delivery of the same share isn't sent again (a later hearing day's share is a new share; replay is CIMD-4259) |
| `SKIPPED_NO_RESULT_CODE` | no result has a code to send (unknown in reference data, or a code over 10 characters); `error_detail` lists the dropped results. GOB is not contacted and nothing is sent to hearing-nows (feature 003 FR-010) | usually none |
| `SKIPPED_NOTHING_NEW` | a later share with nothing new for GOB (every offence already sent, or the rest adjourned); `error_detail` gives counts. The most recent GOB data is handed over again | none |

Feature 003: each notification ends in one row of `enforcement_notification` with a final status (data-model.md):
`PUBLISHED` (GOB data event sent to hearing-nows), `SKIPPED_NOT_ENFORCEMENT`, `SKIPPED_NO_RESULT_CODE` (no result
with a code, or only codes over 10 characters; nothing sent), `NO_GOB_DATA` (nothing new, or an amend & reshare, and no
earlier GOB data; NOWS stay held without a time limit), `GOB_FAILED` (see the submission row), `PUBLISH_REJECTED` (`last_error`, e.g.
`NO_ACCOUNT_NUMBER`), or `STORED_FAILURE` (`failed_stage`, `last_error`, `next_replay_at`). The share's enforcement
NOWS stay held in hearing-nows until a GOB data event reaches them. `nows_handover_*` columns on
`hearing_result_submission` are no longer written. Rows are per share (`correlation_id` = the notification id).

> ⚠️ Until payment terms are mapped (CIMD-4251), or GOB makes `paymentDueDate` optional, the default `PAYMENT_DUE_DATE_FALLBACK=NONE` makes **every** submission `MAPPING_FAILED / PAYMENT_DUE_DATE_UNAVAILABLE`. Don't switch this flow on in production before then (research.md open item 12). Run a **single replica** (open item 13).

Reshares have no submission row; see `enforcement_notification` (`is_reshare`). Out-of-scope shapes are not stored;
look for them in the logs by hearing id or `caseUrn`.

```sql
-- One defendant's submission, by caseUrn (or hearing_id / defendant_id)
select id, status, http_status, error_detail, created_at, updated_at
from hearing_result_submission
where case_urn = :case_urn;

-- Outcome unknown at GOB: check the GoB account before replaying
select id, case_urn, hearing_id, defendant_id, error_detail, updated_at
from hearing_result_submission
where status = 'FAILED' and (error_detail like 'INTERRUPTED%' or error_detail like 'TIMEOUT%' or error_detail like 'ERROR%')
order by updated_at desc;

-- Feature 003: notifications not published (NOWS still held in hearing-nows), newest first
select id, hearing_id, hearing_date, status, failed_stage, last_error, next_replay_at, submission_id, updated_at
from enforcement_notification
where status <> 'PUBLISHED'
order by updated_at desc;

-- Which offences of a case have been sent to GOB, and in which submission (feature 002: each offence once)
select o.offence_id, o.judicial_result_id, o.submission_id, s.status, o.created_at
from gob_sent_offence o join hearing_result_submission s on s.id = o.submission_id
where o.case_id = :case_id
order by o.created_at;

-- Recent failures of any kind
select status, count(*) from hearing_result_submission
where updated_at > now() - interval '1 day' and status <> 'SUCCEEDED'
group by status;
```

## Tech stack

- **Java 25**, **Spring Boot 4**, **Gradle**
- Observability: Spring Boot Actuator, OpenTelemetry, Prometheus
- Hosting: Azure (App Insights, ACR/AKS via the ADO mirror pipeline)

## Prerequisites

- ☕️ **Java 25 or later** on your `PATH`
- ⚙️ **Gradle** (the wrapper pins the version — `gradle/wrapper/gradle-wrapper.properties`)

```bash
java -version
gradle -v
```

## Build & test

```bash
./gradlew build          # compile + unit/integration tests (integration tests need Postgres on localhost:5432)
./gradlew test           # tests only
./gradlew pmdMain        # PMD for main sources (runs only when named, per gradle/pmd.gradle)
```

Integration tests run the flow end to end through an embedded Artemis broker, WireMock (reference data,
the gateway and the Results query) and the local Postgres. Most flow cases are data-driven: add a folder under
`src/test/resources/scenarios/` (format in
[quickstart.md](specs/001-cimd-4246-hearing-resulted-to-libra/quickstart.md#integration-test-scenarios-automated)).

### Static analysis (PMD)

```bash
gradle pmdTest
```

## GOB (Libra) simulator

A non-live simulator of the Libra Gateway hearing-event API lives in
[`enforcement-workflow-simulator/`](enforcement-workflow-simulator/README.md). It is a separate Gradle subproject producing its own
boot jar; it is never published, never packaged into this service's image, and refuses to start
under a live Spring profile.

## CI/CD

GitHub Actions workflows live in `.github/workflows`:

- `ci-draft.yml` — build/verify on PRs and branch pushes.
- `ci-released.yml` — on a **published GitHub Release** (`release: [published]`), publishes the
  artefact and triggers the Docker build/deploy via `ci-build-publish.yml` (with a Trivy image scan
  and a release-notes appender that records the published image coordinates).
- `code-analysis.yml`, `codeql.yml`, `secrets-scanner.yml`, `auto-merge-dependabot.yml`.

`main` and `team/*` branches are protected and require at least one approving review.

## Contributing

See [CONTRIBUTING.md](.github/CONTRIBUTING.md). Branch naming: `team/<topic>`.

## License

MIT — see [LICENSE](LICENSE).
