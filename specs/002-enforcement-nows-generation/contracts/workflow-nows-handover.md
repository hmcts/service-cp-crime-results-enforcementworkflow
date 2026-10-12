# Contract: W hand-over to H (`HearingNowsClient`) and recorded outcomes

| Item | Value |
|---|---|
| When | In `HearingResultedProcessor`, right after `SubmissionStore.recordSucceeded(...)` for a submission whose gateway reply was parsed (`GatewayResult.Success` with a non-null response). Never for FAILED, MAPPING_FAILED, SKIPPED_NO_RESULT_CODE, NOT_SENT, or a 2xx whose reply could not be parsed (no GOB data to hand over). |
| Later share (FR-018 to FR-020) | Not a reshare, same hearing id, another `hearingDay` → new `correlationId`, new submission row; only offences not yet in `gob_sent_offence` and not adjourned in this share are sent (plus non-offence results not yet sent); the hand-over carries this share's GOB data and `correlationId`. Nothing to send → `SKIPPED_NOTHING_NEW`, no GOB call, and the most recent GOB data for the case and defendant is handed over with `gobDataReused: true` and `coveredResultDefinitionIds` (as for a reshare); none → no hand-over. |
| Amend & reshare (FR-016) | On a reshare event for an in-scope Enforcement case: **never** a GOB call. If the case and defendant have a `SUCCEEDED` submission with a parsable `response_payload`, resolve the reshare's results to GOB codes, compute `coveredResultDefinitionIds` (reshare results whose GOB code is in any `SUCCEEDED` `request_payload` for them) and hand over the most recent `nowsDataItems` with `gobDataReused: true` and the reshare's `sharedTime`, `hearingDay` and `correlationId` (its hearing day's id); otherwise nothing is handed over (logged with the reason). |
| Payload | Built by `NowsHandoverPayloadBuilder` from the event (ids, caseUrn, hearingDay, sharedTime, `correlationId` from `ShareCorrelationId`, R18), the request sent to GOB (`prosecutorDefendantId` → `accountNumber`), the **raw** gateway reply's `nowsDataItems` JSON node, unchanged (R10), and `coveredResultDefinitionIds` (first share: the results sent). Validated against H's schema copy in tests (constitution II, v1.1.1). |
| Endpoint | [hearingnows-add-enforcement-nows-data.md](hearingnows-add-enforcement-nows-data.md) |
| Config | `cp.hearing-nows.base-url` (`HEARING_NOWS_URL`, e.g. the CP backend host), `cp.hearing-nows.cjscppuid` (`HEARING_NOWS_CJSCPPUID`, placeholder until the platform supplies the system user), `cp.hearing-nows.connect-timeout-ms` 5000, `cp.hearing-nows.read-timeout-ms` 10000 |
| Client behaviour | Never throws. 202 → `HandoverResult.HandedOver`; other status → `Failed("HTTP <status>")`; no response → `Failed(<cause class>)`. No retry. |
| Recorded | `SubmissionStore.recordHandover(id, status, detail)`: `nows_handover_status`, `nows_handover_detail`, `nows_handover_at` (data-model §1). The submission's `status` stays `SUCCEEDED` either way (GOB accepted). |
| Logs | INFO `Submission {id} caseUrn {urn}: GOB data handed over to hearing NOWS` / WARN `... hand-over to hearing NOWS failed: {detail}`; never the payload, account number or GOB data. |
| Resilience | A failed hand-over never re-sends to GOB and never stops the listener (FR-004). |

Support query (README runbook addition):

```sql
select id, correlation_id, case_urn, hearing_id, defendant_id, nows_handover_status, nows_handover_detail, nows_handover_at
from hearing_result_submission
where status = 'SUCCEEDED' and (nows_handover_status is null or nows_handover_status = 'FAILED')
order by updated_at desc;
```
