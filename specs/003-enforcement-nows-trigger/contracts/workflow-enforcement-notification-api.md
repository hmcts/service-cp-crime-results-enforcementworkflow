# Contract: enforcement notification (results-processing app → enforcement workflow)

OpenAPI draft: [enforcement-notification-openapi.yml](enforcement-notification-openapi.yml). Research R1–R5, R17, R22.

| Item | Value |
|---|---|
| Method / path | `POST /enforcement-notifications` |
| Content type | `application/json` |
| Caller | results-processing app (`NotifyEnforcementWorkflow` activity), internal network only |
| Identity | header `CJSCPPUID` = a system user in `cp.notification.allowed-system-users`; FA sends its existing system user (setting `CJSCPPUID`, research R2) |
| FA base URL env | `ENFORCEMENT_WORKFLOW_API_BASE_URI` (host only; FA appends the path) |
| When | once per share, after `ProcessEnforcementCaseNowsVariants` succeeded (research R25), only when the share has NOWs for an Enforcement case's defendant and FA flag `EnforcementNows` is on |

**Body**

```json
{
  "hearingId": "6f1c2b9e-0000-4000-8000-000000000001",
  "hearingDate": "2026-10-19",
  "isReshare": false,
  "nows": [
    { "requestId": "1d2c3b4a-0000-4000-8000-0000000000a1", "masterDefendantId": "9a8b7c6d-0000-4000-8000-0000000000d1" },
    { "requestId": "1d2c3b4a-0000-4000-8000-0000000000a2", "masterDefendantId": "9a8b7c6d-0000-4000-8000-0000000000d1" }
  ]
}
```

(Synthetic ids.) `isReshare` (required, added 2026-10-22, research R22) is FA's `hearingResultedObj.isReshare === true`;
an amend & reshare is never sent to GOB (FR-008). A repeat must carry the same hearing, date and `isReshare` (else 409).

**Responses and caller behaviour**

| Status | Meaning | W records | FA does |
|---|---|---|---|
| 202 `{notificationId}` | stored, stage 1 queued (or a repeat: same ids already stored, existing id returned, nothing queued) | notification `RECEIVED` | continue |
| 400 | schema / validation error | nothing | fail the activity (defect); the HTTP wrapper does not retry, the orchestrator's activity retry repeats it (harmless, nothing stored) |
| 403 | missing or unknown `CJSCPPUID` | nothing | fail the activity (configuration error); repeated by the activity retry as for 400 |
| 409 | some request ids already belong to another notification, or to one for another hearing or date, or with another `isReshare` | nothing | fail the activity (defect); repeated by the activity retry as for 400 |
| 503 / no response | W cannot store | nothing | retry (wrapper + activity retry), then orchestration `Failed` (R5) |

W answers after one transaction (notification + request ids + `jobs` row), well under FA's read timeout (≥ 10 s).

**Tests**: W controller IT per response code (CLAUDE.md "IT per endpoint"), schema validation of FA's request builder
against the OpenAPI (FA unit test), PII-free log assertion.
