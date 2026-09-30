# Quickstart: CIMD-4246 — Hearing Resulted to GOB (Libra)

**Feature**: `001-cimd-4246-hearing-resulted-to-libra` | **Plan**: [plan.md](plan.md)

Applies once iteration 1 (plan §6) is implemented. Environment variables are listed in [data-model.md](data-model.md) §4 and the service README.

**Last validated: 2026-09-27**, end to end over a real Artemis broker. The chain was: workflow (docker profile)
→ real gateway → WireMock as APIM, plus WireMock as reference data, against a local Postgres. Scenarios
1-9 passed on the running services. 10-11 are covered by `FailureOutcomesIntegrationTest`, since they need
a restart mid-call or a short timeout. No defendant PII appeared in either service's log.

```bash
# 1. Contract (until the draft is published)
cd ../api-cp-crime-results-enforcementgateway && ./gradlew build publishToMavenLocal   # version 0.0.999

# 2. Stubs + database (workflow repo)
cd ../service-cp-crime-results-enforcementworkflow
docker compose up -d postgres                  # or an existing Postgres on 5432 with database enforcementworkflowdb
docker compose --profile stubs up -d wiremock  # :8089 serves reference data AND acts as APIM (/hearingResulted)

# 3. Gateway (8082), pointed at WireMock as APIM
cd ../service-cp-crime-results-enforcementgateway
LIBRA_APIM_BASE_URL=http://localhost:8089 LIBRA_APIM_SUBSCRIPTION_KEY=local-key ARTEMIS_LISTENER_ENABLED=false ./gradlew bootRun

# 4. Workflow (8083), listener active
cd ../service-cp-crime-results-enforcementworkflow
ENFORCEMENT_GATEWAY_URL=http://localhost:8082 REFERENCE_DATA_URL=http://localhost:8089 \
PAYMENT_DUE_DATE_FALLBACK=HEARING_DATE SPRING_PROFILES_ACTIVE=docker ./gradlew bootRun

# 5. A broker on 61616 (a throwaway one is fine) and a published fixture event
docker run -d --name ew-quickstart-artemis -p 61616:61616 -e ARTEMIS_USER=admin -e ARTEMIS_PASSWORD=admin \
  apache/activemq-artemis:latest-alpine
docker cp src/test/resources/events/hearing-resulted-enforcement.json ew-quickstart-artemis:/tmp/event.json
docker exec ew-quickstart-artemis sh -c '/opt/activemq-artemis/bin/artemis producer --user admin --password admin \
  --url tcp://localhost:61616 --destination topic://public.event --message-count 1 --message "$(cat /tmp/event.json)" \
  --properties "[{\"type\":\"string\",\"key\":\"CPPNAME\",\"value\":\"public.events.hearing.hearing-resulted\"}]"'

# 6. Check the outcome (the fixtures share hearing/case/defendant ids: delete rows between scenarios)
psql -h localhost -U postgres enforcementworkflowdb -c "select status, http_status, case_urn, error_detail from hearing_result_submission;"

# 7. Clean up
docker rm -f ew-quickstart-artemis && docker compose --profile stubs rm -sf wiremock
```

The workflow must be started **after** the broker is up, or its listeners retry every 5s until it is.
For scenario 8, add a higher-priority WireMock mapping for `POST /hearingResulted` that returns
`404 {"errorCode":"E404",...}`. The gateway turns it into 502, and the workflow records `FAILED` with 502.

## Verification scenarios

| # | Input event (fixture) | Expected |
|---|---|---|
| 1 | GAPGD00, first share, one defendant, resolvable short codes | One `SUCCEEDED` row; the gateway receives `HearingResultedRequest`; `response_payload` is stored |
| 2 | Same event redelivered | No second POST; INFO "already submitted" |
| 3 | `isReshare: true` | No POST, no row; INFO skip `RESHARE` |
| 4 | Non-enforcement OU code | No POST, no row |
| 5 | Two defendants / two enforcement cases / linked application | No POST, no row; WARN with the reason |
| 6 | Only codes outside the enum (e.g. PGPAY) | `SKIPPED_NO_RESULT_CODE` row, no POST |
| 7 | Blank `prosecutionAuthorityReference` / `caseURN` longer than 36 | `MAPPING_FAILED` row, no POST |
| 8 | Gateway returns 502 | `FAILED` row with `http_status` and `error_detail` |
| 9 | No NOWS mapping configured | Request has **no** `nowsDataRequest` |
| 10 | A `SENDING` row older than the stale threshold (simulated restart mid-call), then the same event again, or the sweep runs | The row becomes `FAILED` with "INTERRUPTED – outcome at GOB unknown"; **no** new POST (R19) |
| 11 | The gateway stub delays longer than the workflow's read timeout | `FAILED` with "TIMEOUT – outcome at GOB unknown"; the same event again → **no** new POST (R20) |

## Integration test scenarios (automated)

`./gradlew test` runs the verification scenarios above without Docker services other than Postgres
(research.md R25). `ScenarioIntegrationTest` publishes each scenario's event on `public.event` to an
embedded Artemis broker, the real listener consumes it, and WireMock answers as reference data and the
gateway. Every request the gateway receives is validated against the gateway contract (in the API jar)
and the Libra contract, and every stubbed 2xx reply against the gateway `HearingResultedResponse`.
`JmsHearingResultedIntegrationTest` covers the listener itself: both listeners connect, other
`CPPNAME`s are not consumed, and a malformed message does not stop the next one.

**To add a scenario**, add a folder `src/test/resources/scenarios/<NN-name>/` (run in name order). No test code is needed.

- `scenario.json` (required); unknown fields fail the test:

  | Field | Meaning |
  |---|---|
  | `description` | What the scenario proves, with the story or decision it covers |
  | `event` | Event fixture on the test classpath, e.g. `events/hearing-resulted-enforcement.json` |
  | `events` | Instead of `event`: fixtures published in order, e.g. a redelivery, or a first share then its reshare |
  | `cppName` | Optional `CPPNAME`; default `public.events.hearing.hearing-resulted` |
  | `referenceData` | Result type id → shortCode (`"SC"`), or `{"status": 503}`. Key `"*"` matches any id. Unlisted ids answer 404 |
  | `gateway` | The gateway's reply: `status`, then `body` (JSON) or `bodyText` (sent as is), and optional `delayMs` (over 1000 ms times out). Omit it when the scenario must not reach the gateway |
  | `expected.gatewayPosts` | Required: the number of POSTs the gateway receives |
  | `expected.status` | The row's status; omit it for "no row" |
  | `expected.httpStatus`, `expected.errorDetail` / `expected.errorDetailStartsWith` | Always checked; absent means null |
  | `expected.responsePayloadContains`, `expected.logMustContain`, `expected.logMustNotContain` | Optional text checks on `response_payload` and on the logs. The enforcement fixture's defendant PII is always checked as never logged (FR-017) |

- `expected-request.json` (optional): the exact request the gateway must receive (array order ignored).

`request_payload` is asserted to be stored exactly when the request was sent. The profiles in use are
`docker` (listeners) and `nowsmapping-test` (SC and WC mapped to NOWS data items), and
`payment-due-date-fallback` is `HEARING_DATE`. Later stories (US5/US6, contract v0.5.0) add folders
and fixtures here rather than new test methods.

