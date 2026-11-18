# Contract: `cp.hearing-result.payment-terms` configuration

The workflow reads payment terms results through configuration, so the CP prompt references and value formats (spec
OQ-1, resolved 2026-11-18) can change without a code change. See [data-model.md](../data-model.md) §2 for the keys.

## Defaults (`application.yaml`)

Sources are in spec OQ-1 and research R3.

```yaml
cp:
  hearing-result:
    payment-terms:
      prompts:
        instalment-amount: [IAMT]
        frequency: [PF]
        lump-sum: [LSA]
        instalment-start-date: [instalmentStartDate, ISTD]
        pay-by-date: [PBD]
        payment-card-required: [PAYMENT_CARD_REQUIRED]
        parent-guardian-to-pay: [PARENT_GAURDIAN_TO_PAY]
      frequency-values:          # keyed by the GOB code; captured values are list items, so spaces and dots are kept
        W: [weekly]
        F: [fortnightly]
        M: [monthly]
      yes-values: ["Yes", "true"]
      no-values: ["No", "false"]
      date-formats: [dd/MM/yyyy, yyyy-MM-dd, dd MMM yyyy]   # tried in order; quoted text allowed
```

These are CP reference data, the same in every environment, so `cpp-aks-deploy` needs no change. The scenario tests use
their own fake references (`test-*`, profile `paymentterms-test`); `PaymentTermsApplicationConfigIntegrationTest` runs
these defaults against CP-shaped prompts (`£` amounts, `dd/MM/yyyy` dates, both boolean forms).

## Startup

- One warning naming any empty keys (research R9; none with these defaults); no values logged.
- Startup fails if a `frequency-values` key is not `W`, `F` or `M`, a captured value is under two frequencies, a prompt reference is under two keys, or a
  value is in both `yes-values` and `no-values`.
