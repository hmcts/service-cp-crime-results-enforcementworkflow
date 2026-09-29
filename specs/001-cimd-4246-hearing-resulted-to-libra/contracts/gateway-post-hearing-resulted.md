# Contract: Gateway `POST /hearingResulted` (new; workflow → gateway)

| Item | Value |
|---|---|
| Source of truth | `api-cp-crime-results-enforcementgateway/src/main/resources/openapi/openapi-spec.yml` (branch `dev/CIMD-4246`, added 2026-09-25) |
| Artefact | `uk.gov.hmcts.cp:api-cp-crime-results-enforcementgateway` (generated `uk.gov.hmcts.cp.openapi.api.EnforcementHearingApi`, `uk.gov.hmcts.cp.openapi.model.*`) |
| Operation | `postHearingResulted`, tag `enforcement-hearing`, `EnforcementHearingApi.PATH_POST_HEARING_RESULTED = "/hearingResulted"` |
| Provider | `service-cp-crime-results-enforcementgateway` (`HearingResultedController`) |
| Consumer | `service-cp-crime-results-enforcementworkflow` (`EnforcementGatewayClient`) |
| Access control (FR-018, R21) | Iteration 1: network isolation only, with no ingress route and a NetworkPolicy admitting only the workflow pod (no credentials on the request). Later: `Authorization: Bearer <Entra token>` (PCR pattern), validated for audience and caller. |
| Timeouts (R20) | Caller (workflow): connect 5s / read 50s. Provider (gateway → APIM): connect 5s / read 40s. APIM → Libra: 35s. |

## Request: `HearingResultedRequest`

This is copied verbatim from Libra Gateway Hearing Event API v0.4.0, with the local amendment that `nowsDataRequest` is optional.

- Required: `caseUrn` (≤ 36), `dateOfHearing` (date), `courtHearingLocation` (≤ 7), `defendantDetails` (`prosecutorDefendantId`, `address1`), `paymentTerms` (`paymentDueDate`, `paymentCardRequested`, `parentToPay`), `enforcement` (`prisonSentenceIndicator`), `results` (min 1, each `resultCode` from a 42-value enum).
- Optional: `employerDetails`, `parentGuardianDetails`, `nowsDataRequest` (when present, `nowsDataItems` min 1, unique, names from the 12-value `NowsDataItemName`).
- `additionalProperties: false` throughout. Senders must omit absent optional blocks rather than send `null` (R22).
- Local amendment in this contract copy: `maxLength` removed from enum-typed properties (`paymentTerms`, `paymentCardRequested`, `parentToPay`, `prisonSentenceIndicator`, `resultCode`, `parentToPayFlag`, `creditOrDebit`), because the enum already bounds the value (R22).

## Responses

| Status | Body | Meaning | Workflow handling |
|---|---|---|---|
| 200 | `HearingResultedResponse`: `caseUrn`, `timestamp` and `nowsDataItems` are **required**; `correlationId` is optional. Every property **inside** `nowsDataItems` is optional, so `"nowsDataItems": {}` is valid. Null fields are left out. | Libra accepted; requested NOWS data returned | `SUCCEEDED`, persist the raw body (also when it can't be parsed); WARN if `caseUrn` differs |
| 400 | `ErrorResponse` | Gateway rejected the payload (schema) | `FAILED` |
| 502 | `ErrorResponse`, `details: {libraStatus, errorCode, errorDescription}` | Libra/APIM rejected (any non-2xx, incl. 3xx), failed or timed out (`libraStatus` null); **or** Libra returned 2xx with an empty or invalid body (`libraStatus` 200, `errorCode` `INVALID_RESPONSE`) | `FAILED`, `error_detail` = codes only (`error; libraStatus; errorCode`), not `errorDescription` (R24) |
| 415 | none | Wrong `Content-Type` | not expected from the workflow |

The gateway holds no business logic. It passes the request and response through **semantically**, not byte for byte: unknown fields are dropped, the order of `nowsDataItems` isn't kept (a set), timestamps are normalised, and nulls are left out. See research.md open items 16-17.
