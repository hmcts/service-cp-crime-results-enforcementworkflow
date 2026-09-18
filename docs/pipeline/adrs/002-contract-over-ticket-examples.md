# ADR-002: The published contract is authoritative over the ticket's example bodies

## Status

Accepted

## Context

CIMD-4372's example response bodies and its acceptance-criteria entity names do not validate
against the OpenAPI contract the ticket cites as their source. This was established directly, not
inferred: every one of the ticket's example responses was checked against
`libra-gateway-hearing-events-openapi-v0.3.0.yml` field by field. Full detail is recorded in
[pipeline artifact 001](../artifacts/001-libra-v030-contract-gaps.html).

The concrete gaps found:

- The `NowsDataItems` schema component declares `additionalProperties: false`. AC2 states that
  response keys are the camelCased form of the twelve `NowsDataItemName` request values. Six of
  the twelve do not camelCase to a real property of `NowsDataItems` — e.g. `"Defendant Account"` →
  `defendantAccount`, which does not exist; the schema property is `defendant`. Posting a response
  with the ticket's key would fail schema validation outright.
- Of the remaining six that do camelCase to a real property, several are typed as JSON scalars in
  the schema where the ticket's example shows a nested object (e.g. `accountWarrantNumber` is a
  pattern-constrained string in the schema; the ticket's example shows
  `{"warrantNo": "W000012345"}`).
- Six field labels used by result codes CP can post (`Date of Offence`, `Start time of offence`,
  `End time of offence`, `Reserve Terms`, `Reason for decision`, `[Directions]`) have no
  corresponding property anywhere in the schema.

AC1 (schema-valid response) and AC2 (the ticket's camelCase key rule) cannot both be satisfied as
written — they are mutually exclusive for those six entity names.

**The v0.4.0 contract upgrade did not change this.** The only difference between v0.3.0 and
v0.4.0 of the bundled contract is the `resultCode` enum (24 → 42 values, confirmed by diffing the
two files at adoption time: `NowsDataItems` and every schema nested under it are byte-identical
between versions). The nested shapes the ticket assumes were not adopted. This decision and the
gap it addresses are therefore still current, not superseded.

## Decision

**The published OpenAPI contract is authoritative.** The simulator emits only properties the
contract declares, in the shapes the contract declares. AC2's camelCase rule is re-expressed as an
explicit `NowsDataItemName` → real schema property mapping, held as data in `entity-names.yaml`
(see `CatalogueLoader`, which validates every mapped property against the schema at startup and
refuses to start if one doesn't exist).

Rationale: CP will eventually integrate against the real Libra Gateway, which will serve the
published contract, not the ticket's example bodies. A simulator built to match the ticket's
examples would train CP's integration against a response shape that cannot occur in production.
Matching the contract is the choice that produces a working integration later, at the cost of the
simulator's responses looking different from the ticket right now.

## Consequences

- CP integrates against a simulator whose responses will match the real Libra Gateway's contract
  shape, not the ticket's illustrative examples.
- The CIMD-4372 ticket's example bodies remain wrong against the published contract. Either the
  ticket needs correcting to match the contract, or a future contract version needs to adopt the
  ticket's nested shapes — this has not happened as of v0.4.0, and is an open item with the vendor,
  not something this codebase can resolve unilaterally.
- `"Account History"` (OQ-1) has no single schema property to map to (the schema offers
  `paymentHistory` and `transactionHistory` separately, with no combined property). The interim
  behaviour, encoded in `entity-names.yaml`, is to map it to both — a request for it returns both
  entities. This should be confirmed with the GOB integration team rather than assumed correct
  long-term.
- The six unmapped field labels are marked `unmapped: true` with an explicit reason in
  `field-paths.yaml` rather than approximated onto a nearby property. Where a result code that CP
  can post requires one of these labels (e.g. `Date of Offence` for `BWTU`/`NBWT`/`S136`), that
  label is simply never emitted for that code — this is a known, visible gap rather than a
  fabricated value standing in for missing vendor data.
