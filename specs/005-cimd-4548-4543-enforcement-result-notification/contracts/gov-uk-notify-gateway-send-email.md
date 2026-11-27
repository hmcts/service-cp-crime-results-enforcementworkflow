# Contract: email dispatch via `cp-gov-uk-notify-gateway` (research R1)

Source: `hmcts/cp-gov-uk-notify-gateway` (`contracts/command-send-email-notification.schema.json`,
`contracts/notification-sent.schema.json`, `contracts/notification-failed.schema.json`,
`docs/pipeline/adrs/002-deviations-from-legacy-notify-context.md`) and the design doc
[cp-gov-uk-notify-gateway — Implementation Technical Overview](https://hmcts.atlassian.net/wiki/spaces/PETTA/pages/308969906/cp-gov-uk-notify-gateway+Implementation+Technical+Overview).
Owned by the gateway team; this service is a client, not an owner, of this contract.

## Outbound: `send-email-notification` command

| Item | Value |
|---|---|
| Transport | Azure Service Bus, queue `ng-send-email` (shared, per command type — not per client) |
| Message `subject` | `send-email-notification` |
| `correlationId` | `applicationProperties.correlationId`; propagate this feature's own correlation id |
| `ReplyTo` (ASB message property) | **required.** A command without it is dead-lettered unread by the gateway. Must be set to a queue this service consumes (data-model.md). |
| Body (`additionalProperties:true`, required `notificationId`/`templateId`/`sendToAddress`) | `{notificationId, templateId, sendToAddress, fileUri, personalisation?, clientContext?}` |
| `notificationId` | this service's `enforcement_result_notification.gov_notify_notification_id`; the gateway's idempotency key (PK dedupe + recover-by-reference check before send) |
| `fileUri` | Azure Blob URI (BYO filestore, managed identity); this service does **not** use the legacy `materialUrl` path |
| `clientContext` | opaque passthrough only — not a routing key |

## Inbound (on the `ReplyTo` queue this service owns)

**Correction**: an earlier draft of this table used the legacy public-topic naming convention
(`public.notificationnotify.events.*`) for these events. Confirmed wrong by reading the gateway's own schema
files directly — `contracts/notification-sent.schema.json`'s `$id`/`title` is just `notification-sent`, with no
such prefix — and by the working `cpp-context-staging-dvla` consumer (`NotifyGatewayResultService`), which
matches the inbound ASB message's **`subject`** property against the bare strings below. These are never
broadcast to a topic; they arrive as a direct, point-to-point reply on the `ReplyTo` queue this service supplied
on its own outbound command.

| ASB `subject` | Required fields | On receipt |
|---|---|---|
| `notification-sent` | `notificationId`, `sentTime` | transition the matching row to `SENT` (FR-018: purge interim doc data only now) |
| `notification-failed` | `notificationId`, `failedTime`, `errorMessage` (schema: hand-rebuilt from legacy, omits `failedTask`/absent optionals) | transition the matching row to `EMAIL_FAILED` (FR-017: recoverable without resubmitting the hearing) |
| `notification-validation-failed` | none guaranteed by a schema of its own; the rejected command's own body is echoed back (`originalEventPayload`) for correlation | sent instead of either of the above when the inbound command carries a `ReplyTo` but fails `command-send-email-notification.schema.json`'s own validation; transition the matching row to `EMAIL_FAILED`, logging `reason`/`details` for triage. A command lacking `ReplyTo` entirely is dead-lettered unread instead — no event reaches this service in that case. |

`notification-sent` is a byte-for-byte legacy golden-master copy (`additionalProperties:false`); do not add fields
to a producer-side command expecting the gateway to echo them back — anything needed later must be looked up from
this service's own row by `notificationId`, not round-tripped through the gateway.

## Known gaps to carry into implementation (not blocking the spec)

- **OQ-4** (gateway's own doc): `cp-task-manager` has no stale-lock/crash recovery for in-flight send tasks — a
  crash mid-send can strand a notification non-terminal. This is an upstream gap, not something this service can
  work around; if it manifests, the symptom is a row stuck in `EMAIL_REQUESTED` with no event ever arriving.
- **This service is the gateway's first real consumer of its own result events** (R1) — the MVP's only other client
  is fire-and-forget. Treat the `ReplyTo` consumption path as new integration surface requiring its own
  integration test (CLAUDE.md hard rule), not an already-proven pattern.
- Gov.Notify egress-proxy toggle (ADR-001 on the gateway side) ships off by default per environment; a per-env
  go-live check on this service's own rollout, not something this service configures itself.
