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

1. **Consume** `public.events.hearing.hearing-resulted` on a durable subscription
   (`HearingResultedEventListener`, `docker` profile only).
2. **Select** the Enforcement case and defendant (`EnforcementCaseSelector`). Prosecuting authority OU
   code `GAPGD00`. **Reshares are never sent.** More than one enforcement case, more than one defendant,
   or a linked application are ignored with a warning.
3. **Resolve** result shortCodes from reference data and translate them to GOB codes
   (`ReferenceDataClient`, `ResultCodeResolver`: TFOUT→TFOOUT, WC→DW, WWDN→WDN; codes GOB doesn't
   list are dropped).
4. **Choose** the NOWS data items GOB should return (`NowsDataItemSelector`; mapping in config).
5. **Build** the `HearingResultedRequest` (`HearingResultedRequestMapper`). `caseUrn` and the GoB
   account number are passed through unchanged.
6. **Submit** it through the gateway (`EnforcementGatewayClient`) and **store** the request, GOB's reply
   and the outcome in Postgres (`SubmissionStore`, table `hearing_result_submission`). Each
   hearing/case/defendant is submitted at most once, and there are no retries (CIMD-4259 will add them).

Still to come: full defendant/result detail and the other payload blocks (CIMD-4247…4256; spec US5/US6),
NOWS document generation, and the reshare email.

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
| `REFERENCE_DATA_URL` | `http://localhost:8081` | reference-data query API base (shortCode lookup) |
| `REFERENCE_DATA_CJSCPPUID` | placeholder UUID | CJSCPPUID system user for reference data (**to be confirmed**) |
| `ENFORCEMENT_GATEWAY_URL` | `http://localhost:8082` | `service-cp-crime-results-enforcementgateway` base |
| `ENFORCEMENT_AUTHORITY_CODE` | `GAPGD00` | OU code that identifies an Enforcement case |
| `SPRING_PROFILES_ACTIVE` | none | **Must be `docker` when deployed**: the JMS listeners only run under that profile, so without it no events are consumed |
| `PAYMENT_DUE_DATE_FALLBACK` | `NONE` | **Leave as `NONE` in deployed environments.** `HEARING_DATE` is a local/test/simulator stand-in only (logs a warning at startup) |
| `STALE_SENDING_THRESHOLD` / `STALE_SENDING_SWEEP_INTERVAL` | `PT5M` / `PT1M` | when an interrupted submission is marked failed |

Timeouts follow a budget where each hop gives up before its caller (research.md R20): reference data
5s + 10s; gateway 5s + 50s (the gateway itself uses 5s + 40s towards APIM, and APIM 35s towards Libra).
The shortCode → NOWS data item mapping lives in `cp.hearing-result.nows-data-items-by-short-code` and
takes GOB's `NowsDataItemName` values; an unknown name stops the service at startup.

## Running locally

See [`quickstart.md`](specs/001-cimd-4246-hearing-resulted-to-libra/quickstart.md) for the full steps.

```bash
docker compose up -d postgres                      # or reuse a local Postgres on 5432 with database enforcementworkflowdb
docker compose --profile stubs up -d wiremock      # optional: stubs reference data + the gateway on :8089
./gradlew build                                    # unit + integration tests (need Postgres on 5432)
REFERENCE_DATA_URL=http://localhost:8089 ENFORCEMENT_GATEWAY_URL=http://localhost:8089 \
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
| `MAPPING_FAILED` | could not be built: `CASE_URN_INVALID`, `PROSECUTOR_DEFENDANT_ID_MISSING`, `ADDRESS1_MISSING`, `DATE_OF_HEARING_MISSING`, `COURT_HEARING_LOCATION_INVALID`, `PAYMENT_DUE_DATE_UNAVAILABLE`, `ORGANISATION_DEFENDANT_NOT_SUPPORTED_YET` | fix the source data in CP; a new share for the same hearing isn't sent automatically (replay is CIMD-4259) |
| `SKIPPED_NO_RESULT_CODE` | none of the results is a code GOB accepts; `error_detail` lists the dropped codes | usually none |

> ⚠️ Until payment terms are mapped (CIMD-4251), or GOB makes `paymentDueDate` optional, the default `PAYMENT_DUE_DATE_FALLBACK=NONE` makes **every** submission `MAPPING_FAILED / PAYMENT_DUE_DATE_UNAVAILABLE`. Don't switch this flow on in production before then (research.md open item 12). Run a **single replica** (open item 13).

Reshares and out-of-scope shapes are not stored. Look for them in the logs by hearing id or `caseUrn`.

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

Integration tests run the flow end to end through an embedded Artemis broker, WireMock (reference data
and the gateway) and the local Postgres. Most flow cases are data-driven: add a folder under
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
