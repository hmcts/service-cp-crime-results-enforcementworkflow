# Quickstart: Enforcement Result Notification Email (feature 005)

Validation guide for developers. Synthetic data only. This feature adds **no new inbound endpoint** — it reuses
feature 003's existing `POST /enforcement-notifications` trigger; validate it by watching the **new**
`enforcement_result_notification` table and the **new** external stubs/listeners, not by calling a new endpoint.

## Prerequisites

Artemis must be running on `:61616` (`cpp-developers-docker`), with topic `jms.topic.public.event` — this
feature adds a selector/subscription on the same topic for `public.systemdocgenerator.events.document-available`/
`generation-failed` (plan.md dependency D8), not a new broker. Azure Service Bus has no local embedded-broker
equivalent in this repo; use the fake `GovUkNotifyEmailSender`/reply-listener pair wired under the test profile
(research.md R12) to exercise the email-dispatch leg locally, rather than a real ASB namespace.

```bash
docker compose up -d postgres                       # database enforcementworkflowdb
docker compose --profile stubs up -d wiremock       # + NEW stubs: enforcement_area, generate-document (:8089)
./gradlew build                                     # unit (100% coverage target) + integration tests, PMD
REFERENCEDATA_URL=http://localhost:8089 ENFORCEMENT_GATEWAY_URL=http://localhost:8089 RESULTS_URL=http://localhost:8089 \
ENFORCEMENT_AREA_URL=http://localhost:8089 DOC_GENERATOR_URL=http://localhost:8089 \
RESULTS_CJSCPPUID=5f1a6c2e-0000-4000-8000-00000000fa11 \
ARTEMIS_BROKER_URL=tcp://localhost:61616 CP_MESSAGING_PUBLIC_EVENT_TOPIC=jms.topic.public.event \
CP_RESULT_NOTIFICATION_BLOB_AZURITE_ENABLED=true CP_RESULT_NOTIFICATION_BLOB_CONNECTION_STRING='<azurite test key>' \
SPRING_PROFILES_ACTIVE=docker ./gradlew bootRun
```

## Smoke test — FIRST_SHARE, trigger shortCode present

Same trigger call as feature 003; WireMock's `enforcement_area` stub returns a team email for the synthetic
Division Code, and `generate-document` accepts the BYO-FS command (202, no body needed — completion comes back
as an Artemis event, not a response):

```bash
curl -i -X POST http://localhost:8083/enforcement-notifications \
  -H 'Content-Type: application/json' -H 'CJSCPPUID: 5f1a6c2e-0000-4000-8000-00000000fa01' \
  -d '{"hearingId":"<uuid>","hearingDate":"2026-11-24","isReshare":false,"nows":[{"requestId":"<uuid>","masterDefendantId":"5a7d2c91-6e3b-4f0a-8c4d-2b9e1f6a3d70"}]}'
# expect 202 {"notificationId": ...} — unchanged from feature 003; this feature's own row is created
# independently (research.md R4) and is not visible in this response.
```

Watch `enforcement_result_notification` progress: `REQUESTED → DOC_REQUESTED` (immediately, once the trigger
shortCode gate and Division Code lookup both pass) `→ DOC_READY` (once the WireMock-stubbed
`document-available` Artemis event is published, or the real doc-generator responds in a fuller local setup)
`→ EMAIL_REQUESTED → SENT` (once the fake ASB reply publishes `notification-sent`).

## Smoke test — AMEND_RESHARE, FR-011 gate re-evaluated (Clarification 2026-11-24)

Reshare the same hearing with `isReshare: true` after amending a result. **The outcome now depends on whether
the current (post-amendment) state still has a trigger shortCode present** — this is the reversed rule from
this session's `/speckit.clarify`, not the feature's original design:

- Current state has a trigger shortCode → a new row reaches `DOC_REQUESTED` as above, diffed against the most
  recent `SENT` row for the same `hearing_id`+`related_reference_number` (the Previous Share Snapshot,
  research.md R3).
- Current state has no trigger shortCode (e.g. the amendment only touched a non-trigger result) → the row is
  created and immediately `SUPPRESSED`, no email at all, even though `isReshare = true`.

## Support queries (read-only; never select `result_short_codes`/blob URIs into tickets — treat as potentially
sensitive per plan.md dependency D2 until the ADR is resolved)

```sql
select id, hearing_id, related_reference_number, notification_type, status, last_error, purged_at
from enforcement_result_notification where hearing_id = :hearingId;

-- most recent SENT row for the Previous Share Snapshot read (idx_ern_hearing_ref_prev):
select id, result_short_codes from enforcement_result_notification
where hearing_id = :hearingId and related_reference_number = :ref and status = 'SENT'
order by received_at desc limit 1;

-- manual recovery of a DOC_FAILED/EMAIL_FAILED row (R11 — no automatic sweeper; support re-queues manually):
-- re-invoke the relevant cp-task-manager task against this row's own id, per its own retry component.
```

## End to end (dev environment, after a joint release)

1. Deploy with `team/CHD-2786` (`cpp-context-system-doc-generator`) merged and released (plan.md D4) — PDF
   rendering cannot go live before this.
2. Confirm Workload Identity RBAC is granted on the new Blob container and the `ng-send-email`/
   `enforcementworkflow-notification-result` Service Bus queues (plan.md D3) before the first real-environment
   run.
3. Share a synthetic Enforcement/Confiscation hearing result with a trigger shortCode: exactly one
   `enforcement_result_notification` row reaches `SENT`, the two blobs are purged immediately after (never
   before), and `result_short_codes` remains on the row.
4. Amend and reshare it three times in a row (spec.md User Story 2 Acceptance Scenario 6): three separate rows,
   three separate emails, each diffed against its own immediately-preceding `SENT` row, not the original first
   share.
5. Amend a hearing whose first share was suppressed (no trigger shortCode) so that the amendment **adds** one:
   this reshare becomes the hearing's first-ever email (research.md R9) — confirm this specific path, since it
   is the behavior this session's clarification session reversed from the original design.
