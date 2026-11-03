# Contract: hearing results sources used by the enforcement workflow (stage 1)

Research R7, R8, R17. Read-only; both owned by `cpp-context-results`.

## 1. Results cache (Azure Cache for Redis)

| Item | Value |
|---|---|
| Key | `INT_{hearingId}_{hearingDate}_result_` (`hearingDate` = notification `hearingDate`, `yyyy-MM-dd`) |
| Value | JSON: the share's public-event payload `{hearing, sharedTime, isReshare, hearingDay, …}` (same shape as W's `HearingResultedEvent`) |
| `isReshare` | The cached `isReshare` is ignored; the notification's `isReshare` is used (R22, `HearingResultsSource`). |
| TTL | 24 h (owner's setting) |
| Auth | Microsoft Entra ID, W managed identity (no access key) |
| Config | `cp.results-cache.enabled` (default `false` until OP-4 is done), `REDIS_HOST`, `REDIS_PORT`, SSL on; connect 2 s, command 2 s |
| Miss / disabled / error | fall back to §2 (an error is logged as a warning, not a failure) |

## 2. Results query API

| Item | Value |
|---|---|
| Request | `GET {RESULTS_URL}/results-query-api/query/api/rest/results/hearingDetails/internal/{hearingId}` |
| Accept | `application/vnd.results.hearing-details-internal+json` |
| Header | `CJSCPPUID` = `RESULTS_CJSCPPUID` (a System Users member) |
| Response | `{hearing, sharedTime}` of the **latest** hearing day (accepted interim, OP-1); `200 {}` when unknown |
| Timeouts | connect 5 s, read 10 s |
| Mapping | `HearingResultedEvent(hearing, isReshare = notification isReshare (R22), sharedTime, hearingDay = notification hearingDate)` |

## Readiness and failures

- "Not ready": no value from §1 and `{}` or a hearing without prosecution cases from §2 → stage 1 retries (R10).
- `5xx`, timeouts, connection errors → retry; after the retries → `STORED_FAILURE` (`PROCESS`), automatic replay.
- `401/403` from §2 → `STORED_FAILURE` with the status (configuration problem; replay after the fix).
