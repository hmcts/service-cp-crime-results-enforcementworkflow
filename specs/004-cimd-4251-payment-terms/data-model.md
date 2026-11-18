# Data Model: Payment terms in the hearing result sent to GOB (CIMD-4251)

**Feature**: [spec.md](spec.md) | **Research**: [research.md](research.md) | **Date**: 2026-11-15

No database change: no new table, column or migration. Mapping failures keep using the existing
`hearing_result_submission` row (`status = MAPPING_FAILED`, reason at the start of `error_detail`, a text column).

## 1. Input: payment terms result (in memory)

`ResolvedResult` (existing, `service` package) gains one field:

| Field | Type | Source | Notes |
|---|---|---|---|
| offenceId, judicialResultId, judicialResultTypeId, cpShortCode, gobCode, adjournmentMarker, orderedDate | existing | unchanged | |
| **prompts** | `List<JudicialResultPrompt>` (never null) | event `judicialResults[].judicialResultPrompts` | `promptReference`, `label`, `value`; empty list when absent |

A **payment terms result** is a `ResolvedResult` in the submission (after `OffenceResultFilter`) whose `cpShortCode` is
one of `INSTL`, `LUMSI`, `PDATE`, `PTFOR` (fixed in code: the CIMD-4251 business rule, not configuration).

## 2. Configuration: `cp.hearing-result.payment-terms` (`PaymentTermsProperties`, `config` package)

| Key | Type | Default (`application.yaml`) | Meaning |
|---|---|---|---|
| `prompts.instalment-amount` | list of prompt references | `IAMT` | instalment amount (CURR) |
| `prompts.frequency` | list | `PF` | payment frequency (fixed list) |
| `prompts.lump-sum` | list | `LSA` | lump sum amount (CURR) |
| `prompts.instalment-start-date` | list | `instalmentStartDate`, `ISTD` | instalment start date (DATE) |
| `prompts.pay-by-date` | list | `PBD` | date to pay in full by (DATE) |
| `prompts.payment-card-required` | list | `PAYMENT_CARD_REQUIRED` | payment card required (BOOLEAN) |
| `prompts.parent-guardian-to-pay` | list | `PARENT_GAURDIAN_TO_PAY` | parent / guardian to pay (BOOLEAN) |
| `frequency-values` | map `W`/`F`/`M` → list of captured values | `weekly` / `fortnightly` / `monthly` | matched ignoring case and surrounding spaces; a captured value under one frequency only |
| `yes-values` | list | `Yes`, `true` | values meaning yes for the two yes/no prompts (ignoring case) |
| `no-values` | list | `No`, `false` | values meaning no |
| `date-formats` | list of date patterns | `dd/MM/yyyy`, `yyyy-MM-dd`, `dd MMM yyyy` | tried in order |

Validation at startup: `frequency-values` keys must be `W`, `F` or `M` (otherwise startup fails, like the other
`@Validated` settings); a prompt reference may not appear under two keys; `yes-values` and `no-values` may not
overlap. Empty lists are allowed and produce the R9 warning.

Removed: `cp.hearing-result.payment-due-date-fallback` (`PAYMENT_DUE_DATE_FALLBACK`) and enum `PaymentDueDateFallback`.
A leftover `PAYMENT_DUE_DATE_FALLBACK` environment variable is ignored by Spring.

## 3. Output: `paymentTerms` block (contract `PaymentTerms`, generated model)

| Field | Contract | INSTL | LUMSI | PDATE / PTFOR | Rule |
|---|---|---|---|---|---|
| `instalmentFrequency` (Libra v0.8.0; was `paymentTerms` in v0.4.0) | `W`/`M`/`F`, optional | frequency | frequency | absent | FR-003, FR-005, FR-006 |
| `paymentDueDate` | date, **required** | start date | start date | pay-by date | FR-003, FR-005, FR-009 |
| `instalmentAmount` | number, optional (required with frequency) | amount | amount | absent | FR-003, FR-009 |
| `lumpSum` | number, optional | absent | lump sum | absent | FR-003, FR-004 |
| `paymentCardRequested` | `Y`/`N`, **required** | flag | flag | flag (PTFOR: prompt not offered → `N`) | FR-007 |
| `parentToPay` | `Y`/`N`, **required** | flag | flag | flag (PTFOR: prompt not offered → `N`) | FR-008 |
| `enforcementOverride*` | optional | never | never | never | FR-012 |

`HearingResultedRequest.paymentTerms` is set to this block, or left null (omitted from JSON) when the submission has no
payment terms result (FR-001, FR-002).

## 4. Mapping outcome (`PaymentTermsMapper`)

```text
PaymentTermsOutcome
├── None                      no payment terms result             → request without paymentTerms
├── Block(PaymentTerms)       one block (or several equal blocks) → request with paymentTerms
└── Unsendable(reason, detail)                                     → MappingResult.Failed → MAPPING_FAILED
```

## 5. Failure reasons (`MappingFailureReason`)

| Change | Value | When | `error_detail` example (no values) |
|---|---|---|---|
| added | `PAYMENT_TERMS_INCOMPLETE` | required prompt missing or blank | `PAYMENT_TERMS_INCOMPLETE: INSTL instalment-amount missing` |
| added | `PAYMENT_TERMS_UNREADABLE` | amount / date / frequency / yes-no not interpretable, or no references configured | `PAYMENT_TERMS_UNREADABLE: LUMSI frequency not a configured value` |
| added | `PAYMENT_TERMS_CONFLICTING` | several payment terms results with different blocks | `PAYMENT_TERMS_CONFLICTING: INSTL, PDATE` |
| removed | `PAYMENT_DUE_DATE_UNAVAILABLE` | no longer produced | existing rows keep their text |

## 6. Submission lifecycle

Unchanged (feature 001/002/003). A payment terms mapping failure ends the share as `MAPPING_FAILED` before any
`SENDING` row or gateway call, exactly like the existing mapping failures; it is not retried, and a re-share follows
the existing re-share rules.
