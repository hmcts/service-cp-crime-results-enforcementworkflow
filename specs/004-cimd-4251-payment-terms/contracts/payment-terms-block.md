# Contract: `paymentTerms` in `POST /hearingResulted` (workflow → gateway → Libra)

**Reference**: Libra Gateway Hearing Event API **v0.8.0** (`src/test/resources/contracts/libra-gateway-hearing-events-openapi-v0.8.0.yml`, an unchanged copy, 2026-11-18). The frequency field is `instalmentFrequency` (Libra's name since v0.6.0; the v0.4.0 name `paymentTerms` was a bug in this feature's first build, fixed 2026-11-18).

**Owner of the schema**: `api-cp-crime-results-enforcementgateway` (`openapi-spec.yml`, `HearingResultedRequest`,
`PaymentTerms`), copied from Libra's Gateway Hearing Event API. This feature changes no endpoint; it changes what the
workflow puts in the existing request.

## Contract change (done 2026-11-15, to be merged and published)

```yaml
HearingResultedRequest:
  required: [caseUrn, dateOfHearing, courtHearingLocation, defendantDetails, enforcement, results]   # paymentTerms removed
  properties:
    paymentTerms:
      $ref: "#/components/schemas/PaymentTerms"
      # LOCAL AMENDMENT (CIMD-4251, 2026-11-15): paymentTerms removed from `required`, as in Libra's contract v0.6.0 ...
PaymentTerms:
  required: [paymentDueDate, paymentCardRequested, parentToPay]   # unchanged
```

Backward compatible: every request valid before is still valid.

## Request shapes the workflow sends

No payment terms result (CIMD-4251 AC2):

```json
{ "caseUrn": "…", "dateOfHearing": "…", "courtHearingLocation": "…", "defendantDetails": { … },
  "enforcement": { "prisonSentenceIndicator": "N" }, "results": [ { "resultCode": "MPSO" } ] }
```

INSTL (AC1 / 3.1):

```json
"paymentTerms": { "instalmentFrequency": "W", "paymentDueDate": "2026-06-12", "instalmentAmount": 500,
                  "paymentCardRequested": "N", "parentToPay": "N" }
```

LUMSI (3.2): INSTL plus `"lumpSum": 2500`.

PDATE / PTFOR (3.3, 3.4):

```json
"paymentTerms": { "paymentDueDate": "2026-06-30", "paymentCardRequested": "N", "parentToPay": "N" }
```

PGPAY with both prompts yes (3.6): `"paymentCardRequested": "Y", "parentToPay": "Y"`.

## Guarantees

- `paymentTerms` is present only with a payment terms result; then the three required fields are always present and
  `instalmentAmount` is present whenever `paymentTerms.instalmentFrequency` is (FR-009).
- Enforcement override fields are never sent (FR-012).
- Every request is validated against this contract in the workflow's tests (`GatewayContract`, constitution VII).

## Consumers

| Component | Change |
|---|---|
| Workflow | builds the block (this feature) |
| Gateway | none in code; version bump; IT: request without `paymentTerms` forwarded |
| Libra / simulator | Libra v0.8.0: block optional, frequency `instalmentFrequency`; the simulator now bundles v0.8.0 (it takes `paymentTerms` as a free map, so only the workflow's `LibraContract` test checks the block's shape) |
