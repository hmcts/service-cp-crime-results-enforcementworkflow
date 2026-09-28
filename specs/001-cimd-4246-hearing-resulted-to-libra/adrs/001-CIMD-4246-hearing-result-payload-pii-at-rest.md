# ADR 001 — CIMD-4246: Hearing-result payloads stored with PII at rest (plain, iteration 1)

- **Status**: Proposed. Needs tech lead sign-off (research.md open item 6).
- **Date**: 2026-09-27
- **Feature**: `specs/001-cimd-4246-hearing-resulted-to-libra`
- **Constitution**: Principle IV (Personal Data Protection) requires this decision to be recorded before the data is stored.

## Context

`service-cp-crime-results-enforcementworkflow` stores every hearing-result submission to GOB (Libra) in the
Postgres table `hearing_result_submission` (data-model.md §3):

- `request_payload` (`jsonb`), the `HearingResultedRequest` as sent. It holds defendant PII: forename,
  surname, date of birth, National Insurance number, address lines, postcode, telephone numbers and the
  GoB account number. Later iterations add employer and parent/guardian details.
- `response_payload` (`jsonb`), the `HearingResultedResponse` as received. Depending on the requested NOWS
  data items, it can hold defendant and account details, payment and transaction history, and **bank
  details** (`CtBankDetails`: account name, number and sort code).

These rows are needed for the later NOWS document-generation step (gap #16), for replay (CIMD-4259) and for
support follow-up (SC-004).

`service-cp-crime-results-pcr` faced the same question and chose plain storage for now, with field-level
encryption as the intended end state (its ADR `004-AMP-891-carry-defendant-pii-encrypted-at-rest.md`).
Principle IV says this repository must make its own decision rather than inherit PCR's.

## Decision

For iteration 1, the request and response payloads are stored **in plain form** (`jsonb`), with these
controls:

1. Only the service's own database role can access the table. There is no shared or reporting access.
2. PII is never written to application logs. This is enforced by tests in both services (FR-017; tasks
   T022 and T041).
3. The support runbook queries (T087) select status and reason columns only, never the payload columns.
5. `error_detail` holds only error codes (`error`, `libraStatus`, `errorCode`) and fixed reason texts. Libra's free-text `errorDescription` is not stored, because it may echo payload values (research.md R24, open item 18). The mapping-failure details never contain field values.
4. The database has platform-level encryption at rest, as for other CP service databases (to be confirmed
   at deploy onboarding).

Field-level encryption of `request_payload`/`response_payload` is the intended end state. It is scheduled
for iteration 4+ (tasks.md T084 / plan §5), following the approach referenced in PCR's ADR-004.

## Consequences

- Iteration 1 can be delivered without an encryption dependency (Key Vault-backed key management).
- Anyone with DB-level access to this service's database can read defendant PII and bank details. This is
  an accepted interim risk that needs tech lead sign-off.
- There is no retention sweep yet (research.md open items; tasks.md T083), so data accumulates until one is agreed.
- When encryption is introduced, a migration must encrypt the existing rows. Queries must not rely on JSON
  operators over the encrypted payload columns, so the support queries stay limited to plain columns.

## Alternatives considered

- **Field-level encryption now**: rejected for iteration 1 because it delays the MVP and the key-management
  approach isn't agreed yet.
- **Not storing the payloads**: rejected. The response is needed for NOWS generation, and the request for
  replay and investigation.
- **Storing only selected fields**: rejected while the GOB contract is still changing (v0.5.0+), because it
  would need a schema change for every contract change.
