# Research: Payment terms in the hearing result sent to GOB (CIMD-4251)

**Feature**: [spec.md](spec.md) | **Date**: 2026-11-15

Decisions use the format decision / rationale / alternatives. "001 R13" etc. refer to
`specs/001-cimd-4246-hearing-resulted-to-libra/research.md`.

## Current state (verified in code, 2026-11-15)

- `HearingResultedRequestMapper` always adds a stand-in `paymentTerms` (`paymentDueDate` = hearing day, both flags
  `N`) and, with `payment-due-date-fallback=NONE` (default, and STE), fails any share containing INSTL/LUMSI/PDATE/PTFOR
  as `PAYMENT_DUE_DATE_UNAVAILABLE` ("payment terms mapping not yet implemented (CIMD-4251)").
- `JudicialResult.judicialResultPrompts` (`promptReference`, `label`, `value`) is already parsed from the event, but
  `ResolvedResult` does not carry it, so the mapper cannot see prompt values.
- The mapper receives only the results left after `OffenceResultFilter` (offences already sent or adjourned removed),
  so results outside the submission never reach it.
- Every CP code, including INSTL etc., is already sent in `results[]` (feature 003 FR-010); this feature does not
  change that.
- The gateway contract (`api-cp-crime-results-enforcementgateway`) made `paymentTerms` optional on 2026-11-15 (LOCAL
  AMENDMENT CIMD-4251, as Libra v0.6.0). Inside the block `paymentDueDate`, `paymentCardRequested`, `parentToPay` stay
  required. The simulator already validates against Libra v0.6.0 (block optional). The gateway is generated from the
  contract and has no payment-terms logic.

## R1 - Payment terms block optional; no stand-in date (resolves 001 T066 `paymentDueDate` ruling / 001 R13)

- **Decision**: with no payment terms result in the submission, `paymentTerms` is omitted. The
  `payment-due-date-fallback` setting (`PaymentDueDateFallback`, `HEARING_DATE`/`NONE`), its startup warning and the
  `PAYMENT_DUE_DATE_UNAVAILABLE` reason are removed.
- **Rationale**: CIMD-4251 AC2 and Libra v0.6.0 make the block optional (user decision 2026-11-15). Omitting it is the
  safe option (constitution IX): GOB keeps its current arrangement instead of receiving an invented date.
- **Alternatives**: keep the setting for local runs (rejected: no use once the block is optional, and it is a less
  safe path that could be switched on by mistake); keep `PAYMENT_DUE_DATE_UNAVAILABLE` (rejected: no longer produced;
  historic rows keep their text in `error_detail`, no schema involved).

## R2 - Carry prompts with each resolved result

- **Decision**: `ResolvedResult` gains `prompts` (the result's `judicialResultPrompts`, empty list when absent),
  filled by `ResultCodeResolver`.
- **Rationale**: the filter already keeps results with their offence; carrying prompts on the same record means the
  payment terms are read only from results that are part of the submission (spec edge case), with no second pass
  over the event.
- **Alternatives**: re-read the event in the mapper by `judicialResultId` (rejected: duplicates the
  offence/defendant-level gathering logic of `ResultCodeResolver`).

## R3 - Prompt references and value formats are configuration (OQ-1 resolved 2026-11-18)

- **Decision**: a `cp.hearing-result.payment-terms` configuration block holds, per logical value, the CP prompt
  reference(s) to read, plus the value formats:
  - `prompts.instalment-amount`, `prompts.frequency`, `prompts.lump-sum`, `prompts.instalment-start-date`,
    `prompts.pay-by-date`, `prompts.payment-card-required`, `prompts.parent-guardian-to-pay` (lists of prompt
    references, matched exactly against `promptReference`);
  - `frequency-values` (`W`/`F`/`M` → list of captured values, matched ignoring case and surrounding spaces; keyed by
    the GOB code because Spring drops spaces and dots from map keys, review 2026-11-18);
  - `yes-values` / `no-values` (captured values for the two yes/no prompts);
  - `date-formats` (patterns tried in order).
  All lists and maps default to **empty** except `date-formats`, which defaults to ISO `yyyy-MM-dd` (the format of
  every other date in the event). Test configuration supplies its own clearly fake references.
- **Rationale**: the user deferred OQ-1 (2026-11-15). Configuration lets the whole feature be built and tested now;
  answering OQ-1 is then a change to `application.yaml` defaults only (the references are CP reference data, the same
  in every environment). With empty defaults a payment terms result cannot be read, so the share is unsendable with a
  reason - the same safe behaviour as today, never invented data (constitution IX).
- **Alternatives**: hard-code `IAMT`, `PF`, ... as references (rejected: guessing - the ticket names prompts, not
  confirmed `promptReference` values); match on prompt label (rejected: labels are display text and can change).
- **Owner**: the developer, with the BA, when OQ-1 is answered from reference data or a real resulted event.
- **Resolved (2026-11-18, user: resolve before STE testing)**: `application.yaml` now holds the values (spec OQ-1),
  each taken from CP itself, nothing guessed:

  | Key | Value | Evidence |
  |---|---|---|
  | `prompts.*` | `IAMT`, `PF`, `LSA`, `instalmentStartDate` + `ISTD`, `PBD`, `PAYMENT_CARD_REQUIRED`, `PARENT_GAURDIAN_TO_PAY` | `cpp-platform-priming` `criminalCourtsResultDefinitions` for INSTL (`6d76b10c-…`), LUMSI (`272d1ec2-…`), PDATE (`bcb5a496-…`), PTFOR (`a0b426c1-…`); `ISTD` in the older definitions copied into `cpp-context-hearing` tests |
  | `frequency-values` | `weekly` / `fortnightly` / `monthly` | fixed list `e555e078-5dd8-11e8-9c2d-fa7ae01bbebc` (codes WKLY / FTNLY / MTHLY); the event carries the value, not the code |
  | amounts | `£` prefix (already accepted by the parser) | hearing `PublishResultUtil.reformatValue` (CURR) |
  | `date-formats` | `dd/MM/yyyy`, `yyyy-MM-dd`, `dd MMM yyyy` | hearing `PublishResultUtil` publishes `dd/MM/yyyy` (from `yyyy-MM-dd`); hearing's own accepted forms (`Constants.DATE_FORMATS_WITHOUT_TIME`) |
  | `yes-values` / `no-values` | `Yes`, `true` / `No`, `false` | hearing `TypeUtils.convertBooleanPromptValue` (Yes/No) and `PublishResultsDelegateV3` (drops `PARENT_GAURDIAN_TO_PAY` = `"false"`); captured events in `cpp-context-results` tests show both |

  Captured `public.events.hearing.hearing-resulted` samples in `cpp-context-results` tests agree (`£150.00`,
  `11/04/2026`, `monthly`, `true`). Tested by `PaymentTermsApplicationConfigIntegrationTest`; STE confirms (SC-003).

## R4 - Mapping per payment terms result (CIMD-4251 data-mapping table)

- **Decision** (`PaymentTermsMapper`, `mapper` package):

  | Result | Required prompts | Block |
  |---|---|---|
  | INSTL | instalment amount, frequency, start date | `instalmentFrequency`, `instalmentAmount`, `paymentDueDate` (= start date), flags |
  | LUMSI | lump sum, instalment amount, frequency, start date | INSTL + `lumpSum` |
  | PDATE | pay-by date | `paymentDueDate` (= pay-by date), flags |
  | PTFOR | pay-by date | `paymentDueDate` (= pay-by date), flags |

  Flags: `paymentCardRequested` = `Y` only when the payment card prompt is present with a yes value; `parentToPay` =
  `Y` only when the parent/guardian prompt is present with a yes value; `N` when absent. A present prompt whose value
  is in neither `yes-values` nor `no-values` is unreadable. Enforcement override fields are never set.
- **Rationale**: one-to-one with the ticket's mapping table and scenarios 3.1-3.6.
- **Alternatives**: none considered; the table is the requirement.

## R5 - Unreadable, missing and conflicting values make the share unsendable

- **Decision**: three new `MappingFailureReason` values, used through the existing `MappingResult.Failed` →
  `MAPPING_FAILED` path:
  - `PAYMENT_TERMS_INCOMPLETE`: a required prompt (R4) is missing or blank;
  - `PAYMENT_TERMS_UNREADABLE`: an amount, date, frequency or yes/no value cannot be interpreted (including when no
    prompt references are configured - detail says so);
  - `PAYMENT_TERMS_CONFLICTING`: the submission has more than one payment terms result and their blocks differ.
  The detail names the result short code and the logical field (for example `INSTL instalment-amount`), never the
  value (FR-014, constitution IV).
- **Rationale**: FR-011; same handling as the other mapping failures (recorded, not retried, no first-share resend).
- **Alternatives**: send the block without the bad field (rejected: the contract requires the three mandatory fields,
  and partial data could overwrite a real arrangement); pick one of several conflicting results (rejected: OQ-2
  default is "do not send", to be confirmed with the BA after implementation).

## R6 - Amount and date parsing

- **Decision**: amounts: an optional leading `£` and surrounding spaces; digits with thousands separators only in
  their proper places (`1,500.00`); unreadable if not a number, negative, more than two decimal places, or a comma
  anywhere else (`1,50` could be a decimal comma, so it is not guessed; review 2026-11-18); sent as a JSON number
  (`BigDecimal`, scale kept). Dates: parsed strictly with `date-formats` in order (quoted text in a pattern allowed);
  unreadable if none matches.
- **Rationale**: FR-010; the ticket's example "£60" style display values (001 T069 note) and Example payloads
  (`50.00`).
- **Alternatives**: rounding to two places (rejected: changes a court amount).

## R7 - Several payment terms results in one submission

- **Decision**: build a block for each payment terms result; equal blocks → one block; different → `PAYMENT_TERMS_CONFLICTING`.
- **Rationale**: OQ-2 default, safe option (constitution IX); duplicates (same arrangement recorded twice) still send.
- **Alternatives**: last ordered date wins (rejected until the BA confirms).

## R8 - Contract versions and the gateway

- **Decision**: after the contract change is merged, the workflow and the gateway move to the draft published from
  that merge (`0.0.0-<merge sha>`); a fixed `X.Y.Z` is still needed before a release build (`validateApiSpecVersions`,
  constitution II). The gateway gets no code change, only the version bump and one integration test proving a request
  without `paymentTerms` is forwarded (CLAUDE.md: integration test per changed endpoint behaviour).
- **Rationale**: constitution I (thin gateway) and II (compile-time contracts).
- **Alternatives**: none.

- **Published (2026-11-18)**: `0.0.0-0d0af98`, from `team/CCT-1222-V2` (PR #50), used by the workflow and the gateway.

## R9 - Startup visibility of the open configuration

- **Decision**: at startup the workflow logs one warning when any `prompts.*` list, `frequency-values`, or
  `yes-values`/`no-values` is empty ("payment terms results will be recorded as unsendable until configured
  (CIMD-4251)"), naming only the empty keys. With the OQ-1 values in `application.yaml` it no longer fires; it stays
  as a guard if a key is ever emptied.
- **Rationale**: makes the behaviour visible in STE logs without reading code (constitution IX); no values logged.
- **Alternatives**: fail startup (rejected: would block every other hearing because of one empty key).

## Open items carried from the spec

| Item | Interim behaviour | Owner | Revisit |
|---|---|---|---|
| OQ-1 prompt references, value formats, frequency mapping | **Resolved 2026-11-18** (R3): set in `application.yaml` from CP reference data and hearing code | Developer | STE testing (SC-003) |
| OQ-2 several different payment terms results | `PAYMENT_TERMS_CONFLICTING`, not sent | BA | After implementation (user, 2026-11-15) |
| OQ-3 Example 1 in CIMD-4251 shows a block with no payment terms result | Block omitted (AC2) | BA | After implementation |
