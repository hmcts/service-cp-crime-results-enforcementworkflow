# Contract: H command `hearingnows.add-enforcement-nows-data` (new, enforcement-specific; W → H)

The new hearing-nows operation dedicated to enforcement, called by W right after GOB's reply (tech lead agreement, 2026-10-09).

| Item | Value |
|---|---|
| Owner / source of truth | `cpp-context-hearing-nows`: `hearingnows-command-api/src/raml/hearingnows-command-api.raml`, schema `src/raml/json/schema/hearingnows.add-enforcement-nows-data.json` (`$ref` `nows-data-items.json` from CIMD-4462 / PR #186) |
| Consumer | `service-cp-crime-results-enforcementworkflow` (`HearingNowsClient`); keeps a verbatim test copy of both schemas in `src/test/resources/contracts/` ("keep in sync; do not edit independently") |
| Method / path | `POST {CP backend}/hearingnows-command-api/command/api/rest/hearingnows/enforcement-nows-data` |
| Content-Type | `application/vnd.hearingnows.add-enforcement-nows-data+json` |
| Headers | `CJSCPPUID: <system user UUID>` (member of "System Users") |
| Feature control | `@FeatureControl("EnforcementNows")` |
| Access control | Drools `add-enforcement-nows-data-command-rules.drl`: action `hearingnows.add-enforcement-nows-data` allowed for group "System Users" only (FR-005) |
| Timeouts (caller) | connect 5s, read 10s |

## Request body

```json
{
  "correlationId": "91f9490e-846c-3c3b-b827-1eac49327338",
  "hearingId": "04180ff1-99b0-40b7-9929-ca05bdc767d8",
  "masterDefendantId": "0ddbc4a7-3c85-4bc3-bdd5-843817320b98",
  "defendantId": "0ddbc4a7-3c85-4bc3-bdd5-843817320b98",
  "prosecutionCaseId": "861aaa70-f142-469d-a7db-9af395347dd5",
  "caseUrn": "E012345678",
  "accountNumber": "1234567890",
  "hearingDay": "2026-05-03",
  "sharedTime": "2026-05-03T14:30:00.000Z",
  "gobDataReused": false,
  "coveredResultDefinitionIds": ["11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"],
  "nowsDataItems": { "accountBalance": 125.5, "accountNumber": "26000123A" }
}
```

Schema rules: all twelve fields required; `hearingDay` ISO date; `gobDataReused` boolean (`true` = W did not call GOB for this share (an amend & reshare, or a later share with nothing new) and hands over the most recent GOB data already received for the case and defendant; GOB is never contacted twice); `coveredResultDefinitionIds` array of UUIDs (result definition ids the GOB data covers; may be empty); ids are UUIDs; `caseUrn` and `accountNumber` `maxLength` 36; `nowsDataItems` is `nows-data-items.json` (identical to GOB's `NowsDataItems`, `additionalProperties: false`, every property optional, `{}` valid); `additionalProperties: false` at the top level.

## Share correlation id (R18; same rule in W and H; interim attribute: hearing day)

```text
correlationId = UUID.nameUUIDFromBytes((hearingId + "|" + hearingDay).getBytes(UTF_8))
  hearingId   lower-case canonical UUID string
  hearingDay  ISO date yyyy-MM-dd (W: event hearingDay; H for a NOW: nowContent.orderDate)
```

Test vectors (both codebases MUST pass them):

| hearingId | hearingDay | correlationId |
|---|---|---|
| `04180ff1-99b0-40b7-9929-ca05bdc767d8` | `2026-05-03` | `91f9490e-846c-3c3b-b827-1eac49327338` |
| `04180ff1-99b0-40b7-9929-ca05bdc767d8` | `2026-05-10` | `74ce7cd2-9d92-31a6-8b7a-ecc365d96b9b` |

H recomputes the id from `hearingId` + `hearingDay` and answers 400 when it differs from `correlationId`. The attribute is interim (research OQ-9): if it changes, only `ShareCorrelationId` (W, H), the NOW-side resolver and these vectors change.

## Responses

| Status | Body | Meaning | W records |
|---|---|---|---|
| 202 | none | Accepted: GOB data kept and/or applied to waiting NOWs (processing continues asynchronously) | `HANDED_OVER` |
| 400 | framework error | Payload does not match the schema, or `correlationId` does not match R18 | `FAILED`, `HTTP 400` |
| 403 | framework error | Toggle `EnforcementNows` off, or caller not in "System Users" | `FAILED`, `HTTP 403` |
| 5xx / no response | — | H error or unreachable / timed out | `FAILED`, `HTTP 5xx` or cause class |

W never retries and never re-sends to GOB (FR-004).

## Behaviour in H (asynchronous after 202)

1. Upsert `enforcement_nows_data` (key `correlationId` + `masterDefendantId`), versioned by `sharedTime`: no row → insert; later `sharedTime` (a reshare of the same hearing day) → replace data, accumulate covered ids, `KEPT`, restart retention; same or earlier → no-op (FR-010).
2. Commit, then complete every `now_document_request` in `WAITING_FOR_ENFORCEMENT_DATA` for the key (conditional update; for reshare data, only NOWs passing the "same type of result" check, others `FAILED` "result not sent to GOB"; see [hearingnows-enforcement-now-handling.md](hearingnows-enforcement-now-handling.md)).
3. On every keep or replace, schedule `RELEASE_ENFORCEMENT_NOWS_DATA_TASK` at the row's `release_at`; it acts only once `release_at` has passed.
4. Logs: correlation id, hearing id, master defendant id, caseUrn, outcome; never `nowsDataItems` or the account number.
