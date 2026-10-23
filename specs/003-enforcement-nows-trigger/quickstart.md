# Quickstart: Triggering NOWS Generation for Enforcement Hearings (feature 003)

Validation guide for developers. Synthetic data only. Never run W builds and H integration tests at the same time
(H's IT script recreates the shared Postgres containers). Before each environment, follow the release checklist in
plan.md ("Release checklist").

## W (enforcement workflow)

Artemis must be running on :61616 (cpp-developers-docker), with topic `jms.topic.public.event`.

```bash
docker compose up -d postgres                       # or the local cpp Postgres (database enforcementworkflowdb)
docker compose --profile stubs up -d wiremock       # stubs reference data, gateway, Results (:8089)
./gradlew build                                     # unit + integration tests, PMD
REFERENCEDATA_URL=http://localhost:8089 ENFORCEMENT_GATEWAY_URL=http://localhost:8089 RESULTS_URL=http://localhost:8089 \
ARTEMIS_BROKER_URL=tcp://localhost:61616 CP_MESSAGING_PUBLIC_EVENT_TOPIC=jms.topic.public.event \
NOTIFICATION_ALLOWED_SYSTEM_USERS=5f1a6c2e-0000-4000-8000-00000000fa01 PAYMENT_DUE_DATE_FALLBACK=HEARING_DATE \
SPRING_PROFILES_ACTIVE=docker ./gradlew bootRun
```

Smoke test (synthetic ids; WireMock returns the Results internal query shape `{hearing, sharedTime}` for the hearing):

```bash
curl -i -X POST http://localhost:8083/enforcement-notifications \
  -H 'Content-Type: application/json' -H 'CJSCPPUID: 5f1a6c2e-0000-4000-8000-00000000fa01' \
  -d '{"hearingId":"<uuid>","hearingDate":"2026-10-19","isReshare":false,"nows":[{"requestId":"<uuid>","masterDefendantId":"5a7d2c91-6e3b-4f0a-8c4d-2b9e1f6a3d70"}]}'
# expect 202 {"notificationId": ...}; then the notification reaches PUBLISHED and one message with
# CPPNAME public.enforcementworkflow.enforcement-nows-data-ready is on the topic
```

Support queries (read-only; never select GOB payload columns into tickets):

```sql
select id, hearing_id, hearing_date, status, failed_stage, last_error, replay_count, next_replay_at
from enforcement_notification where hearing_id = :hearingId;
select request_id, master_defendant_id from enforcement_notification_now where notification_id = :id;
-- manual replay of a stored failure (R10):
update enforcement_notification set next_replay_at = now() where id = :id and status = 'STORED_FAILURE';
```

## H (hearing NOWS service)

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
export CPP_DOCKER_DIR=/Users/macpro/HMCTS/github/cpp-developers-docker
mvn -o clean install && ./runIntegrationTests.sh   # EnforcementNowsIT publishes the public event
```

## FA (results-processing app)

```bash
# from the cpp-context-azure-legalaidagency repo:
cd azure-functions/durable-functions && npm ci && npm test
```

## End to end (dev environment, after the joint release)

1. Switch on `EnforcementNows` in FA and H together.
2. Share results for a synthetic Enforcement case with a NOW-producing result: the NOW is held (`WAITING_FOR_GOB`),
   W's notification reaches `PUBLISHED`, the NOW reaches `ENFORCEMENT_DATA_ADDED` and is generated.
3. Amend & reshare: no second GOB submission; the reshare's NOWs are generated with the first GOB data
   (`gobDataReused=true`). An amend & reshare whose first-time result never reached GOB is not sent either: the
   notification ends `NO_GOB_DATA` and its NOWs stay `WAITING_FOR_GOB` (no time limit).
4. A result code GOB did not use before is sent like any other (needs the gateway service on the contract with the
   open `resultCode`, plan D7).
