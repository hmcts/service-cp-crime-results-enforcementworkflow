# ADR 001 — CCT-1222 / CIMD-4462: GOB account data kept in hearing-nows for enforcement NOWS (plain, iteration 1)

- **Status**: Proposed. Needs tech lead sign-off, and the data-protection decision of spec FR-015, before any shared
  environment holds this data (research.md OQ-4).
- **Date**: 2026-10-09
- **Feature**: `specs/002-enforcement-nows-generation`
- **Constitution**: Principle IV (Personal Data Protection) requires this decision to be recorded before the data is
  stored. Feature 001's ADR covers the enforcement workflow's own table, not `cpp-context-hearing-nows`.

## Context

After GOB accepts an Enforcement case's results, the enforcement workflow hands GOB's reply to
`cpp-context-hearing-nows` (command `hearingnows.add-enforcement-nows-data`). Because the NOW and the hand-over arrive
in either order, hearing-nows has to keep GOB's data until the NOW arrives (research R1, R5):

- `enforcement_nows_data.nows_data_items` (TEXT, JSON): GOB's `nowsDataItems`, verbatim. Depending on the requested
  items it holds defendant and account details, payment and transaction history, impositions and creditors, warrant
  details and **bank details** (`ctBankDetails`).
- `enforcement_nows_data.account_number`: the GOB account number.
- `now_document_request.payload`: the completed NOW, with the same data in `nowContent.financialOrderDetails`
  (`enfNowsDataItems`, `accountPaymentReference`), as hearing-nows already stores every NOW payload today.

## Decision

For iteration 1 the data is stored **in plain form** in hearing-nows' viewstore, like its existing NOW payloads,
with these controls:

1. **Bounded retention** (research R7): every row of `enforcement_nows_data` has `release_at` = time kept + the
   retention period (JNDI `hearingnows.enforcement.nows.data.retention`, default `PT24H`). A delayed jobstore task then
   removes `nows_data_items` and `account_number`, whether or not a NOW used them (`RELEASED`, or `UNCLAIMED` when no
   NOW claimed them). A later hand-over for the same share restarts the period.
2. **Only the enforcement workflow can write it**: the command is restricted to "System Users" (Drools) and behind the
   `EnforcementNows` feature toggle (FR-005).
3. **Never logged**: hearing-nows and the enforcement workflow log ids, the case reference and outcomes only; tests
   assert that GOB values, the account number and defendant details are absent (FR-014; tasks T040/T058, T078).
4. The completed NOW payload follows hearing-nows' existing handling of NOW payloads (file service, document
   generation, material); this feature adds no new copy beyond the kept data above.
5. Platform-level encryption at rest of the viewstore, as for other CPP context databases.

## Consequences

- Enforcement NOWS can be completed in either arrival order without a lookup back to the enforcement workflow.
- Anyone with database access to hearing-nows' viewstore can read GOB account data for up to the retention period
  (and in NOW payloads, as for every NOW today). This is an accepted interim risk that needs sign-off.
- The retention and maximum-wait values are part of the data-protection decision (FR-015); both default to 24 hours.

## Alternatives considered

- **No kept data; the NOW queries the enforcement workflow for GOB data**: rejected. It adds a synchronous dependency
  from a CPP context on a Spring Boot service, and the data would still be held there.
- **Field-level encryption now**: rejected for iteration 1 (no agreed key management in hearing-nows); revisit with the
  enforcement workflow's own encryption plan (feature 001 ADR 001).
- **Keeping the data until the NOW is complete only**: rejected. Several NOWS for one defendant may arrive at
  different times, and an unclaimed hand-over would otherwise be kept forever.
