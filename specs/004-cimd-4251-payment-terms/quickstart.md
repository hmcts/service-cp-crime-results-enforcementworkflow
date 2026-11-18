# Quickstart: Payment terms in the hearing result sent to GOB (feature 004, CIMD-4251)

Validation guide for developers. Synthetic data only; never put real amounts, dates or case references in fixtures or
tickets.

## 1. Contract (api-cp-crime-results-enforcementgateway)

```bash
./gradlew build          # paymentTerms no longer in HearingResultedRequest.required
```

After the merge, note the published draft version (`0.0.0-<merge sha>`, Azure Artifacts `hmcts-lib`) and set it in
the workflow's and the gateway's `build.gradle` (`implementation` and `apiSpec`).

## 2. Workflow (this repo)

```bash
docker compose up -d postgres
./gradlew build          # unit + integration tests (scenario suite) + PMD
```

What to look for:

- `PaymentTermsMapperTest`: every CIMD-4251 scenario (AC1, AC2, AC3 for SC/MPSO/PGPAY/FTTP, 3.1-3.6) and the
  incomplete / unreadable / conflicting cases.
- `ScenarioIntegrationTest`: the existing scenarios now expect **no** `paymentTerms` (no payment terms result in their
  events); the new `49-56 *-payment-terms-*` scenarios expect the block or `MAPPING_FAILED / PAYMENT_TERMS_*`, and no amounts
  or dates in the logs.
- Scenario tests run with fake prompt references (`test-*`) from the test configuration;
  `PaymentTermsApplicationConfigIntegrationTest` runs the real `application.yaml` values (OQ-1) against CP-shaped
  prompts.

Local run: as in `specs/003-enforcement-nows-trigger/quickstart.md`, **without** `PAYMENT_DUE_DATE_FALLBACK` (removed).
No payment terms warning is expected at startup (OQ-1 resolved; the warning only names keys left empty).

Last run (2026-11-17, local contract copy `0.0.0-cimd4251-local`): workflow 506 tests (54 scenarios) + simulator 260,
0 failures; gateway 132 tests, 0 failures; PMD clean in both.

## 3. Gateway (service-cp-crime-results-enforcementgateway)

```bash
./gradlew build          # includes the new IT: a request without paymentTerms is forwarded to APIM unchanged
```

## 4. STE (after deploying the workflow and gateway builds)

**Deploy order** (each step is backward compatible with the one before it, not with the one after):
1. contract merged and published (the services' `build.gradle` take its version);
2. gateway (accepts requests without `paymentTerms` and with `instalmentFrequency`);
3. hearing-nows and the simulator together (`english_instalmentPaymentPeriod`; hearing-nows rejects unknown `Terms` fields);
4. workflow.
A workflow ahead of the gateway would have every request without a payment terms result rejected (old contract:
`paymentTerms` required).

With the OQ-1 values in `application.yaml` (resolved 2026-11-18, research R3):

| Hearing | Expected |
|---|---|
| Enforcement hearing with no payment terms result (e.g. MPSO only, or a warrant) | Sent; request has no `paymentTerms` |
| SC + INSTL, frequency weekly, instalment £500, start date | Sent; `paymentTerms`: `"W"`, 500, `paymentDueDate` = start date, card `N`, parent `N` |
| LUMSI | As INSTL plus `lumpSum` |
| PDATE / PTFOR | Sent; `paymentTerms`: `paymentDueDate` = pay-by date, card `N`, parent `N` (PDATE parent `Y` if ticked) |
| INSTL or LUMSI with payment card and parent/guardian ticked | card `Y`, parent `Y` |
| A payment terms result with a mandatory prompt left empty | Not sent; `MAPPING_FAILED`, `PAYMENT_TERMS_INCOMPLETE` |

If a real share comes back `PAYMENT_TERMS_UNREADABLE` "… not readable", the captured value has a format the
configuration does not list yet: check the prompt's value in the event and add its format to `application.yaml`
(`date-formats`, `frequency-values` or `yes-values`/`no-values`). That is a configuration change only.

Support query (read-only; do not copy payload columns into tickets):

```sql
select id, status, left(error_detail, 60) from hearing_result_submission
where status = 'MAPPING_FAILED' and error_detail like 'PAYMENT_TERMS_%' order by created_at desc;
```
