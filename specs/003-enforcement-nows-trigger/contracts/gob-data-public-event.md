# Contract: GOB data public event (enforcement workflow → hearing NOWS service)

Schema: [public.enforcementworkflow.enforcement-nows-data-ready.json](public.enforcementworkflow.enforcement-nows-data-ready.json)
(draft; owned by H). Research R12, R13, R18.

| Item | Value |
|---|---|
| Event name / `CPPNAME` | `public.enforcementworkflow.enforcement-nows-data-ready` |
| Destination | Artemis topic, configurable `cp.messaging.public-event-topic` (AKS `public.event`; local docker `jms.topic.public.event`) |
| Message | JMS `TextMessage`, persistent, string property `CPPNAME` |
| Body | `{"_metadata": {...}, <payload fields at top level>}` |
| `_metadata` | `id` (new UUID per publish), `name` (= event name), `createdAt` (UTC ISO-8601), `context.user` (W's system user) |
| Producer | W stage 2 task `PUBLISH_GOB_DATA` (one event per notification) |
| Consumer | H `EnforcementNowsDataEventProcessor` (`subscriptions-descriptor.yaml`, durable shared subscription) |
| When | notification `SUBMITTED` (GOB accepted, `gobDataReused=false`) or `SKIPPED_NOTHING_NEW` with earlier GOB data (`gobDataReused=true`): a first-time share with nothing new, or **every amend & reshare** with earlier GOB data (never sent to GOB, FR-008/FR-009) |
| Not published | `SKIPPED_NO_RESULT_CODE`, `SKIPPED_NOT_ENFORCEMENT`, `NO_GOB_DATA` (incl. an amend & reshare with no earlier GOB data), `GOB_FAILED`, `PUBLISH_REJECTED` (no account number, or no notification NOW for the selected defendant's master id) (NOWS stay held, FR-016) |

**Payload**: `hearingId`; `nows[] {requestId, masterDefendantId}` (the notification's NOWs whose master defendant is the defendant GOB answered for; NOWs of any other defendant stay held, see research OP-13); `accountNumber`
(GOB account number, pass-through, constitution VI); `gobDataReused`; `sharedTime` (of the results used, optional);
`nowsDataItems` (GOB's reply item block, verbatim, BigDecimal-preserving, as feature 002).

**H behaviour per `{requestId, masterDefendantId}`** (FR-013, R13)

1. Feature `EnforcementNows` off → log (ids) and drop.
2. NOWs with `request_id` in `WAITING_FOR_GOB` and the same master defendant → write `nowsDataItems` into
   `financialOrderDetails.enfNowsDataItems` and the account number (feature 002 merger) → `ENFORCEMENT_DATA_ADDED`
   (conditional update; a NOW is completed once) → `PROCESS_NOW_DOCUMENT_REQUEST_TASK`.
3. No such NOW → keep in `enforcement_nows_data` (status `KEPT`, `release_at` = now + retention); a NOW arriving later
   with that request id is completed at once; at `release_at` the data is released (`UNCLAIMED` if never claimed).
4. Repeated event (same request id already kept or claimed) → no effect.
5. Master defendant mismatch, totals missing for a financial template, or similar deterministic problems → log (ids
   only) and drop that item; never throw (no redelivery loop). Transient failures (database) throw → redelivery.

**Producer failure**: send failure → task retry (3, back-off) → `STORED_FAILURE` (`PUBLISH`) → automatic replay
schedule; replay re-publishes without contacting GOB (FR-015). H is idempotent per request id.

**Data protection**: ADR 002 in feature 001's ADR folder, [`specs/001-cimd-4246-hearing-resulted-to-libra/adrs/002-CIMD-4530-gob-data-on-public-event.md`](../../001-cimd-4246-hearing-resulted-to-libra/adrs/002-CIMD-4530-gob-data-on-public-event.md) (Proposed).
