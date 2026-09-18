# ADR-003: Jackson 2 and Jackson 3 coexist deliberately in this module

## Status

Accepted

## Context

Spring Boot 4 auto-configures **Jackson 3** (`tools.jackson.databind.json.JsonMapper`) for its
HTTP message conversion. This module's own code — catalogue loading (`CatalogueLoader`,
via `com.fasterxml.jackson.dataformat.yaml`) and NOWS response-tree conversion
(`NowsDataItemsAssembler`, via `com.fasterxml.jackson.databind.ObjectMapper`) — uses **Jackson 2**.
Two consequences of this were found empirically during implementation, not anticipated in the
original design:

1. **Jackson 3 defaults `FAIL_ON_UNKNOWN_PROPERTIES` to `false`**, the opposite of Jackson 2's
   default. Worse, Jackson 3's record deserialiser (as shipped in the `tools.jackson.databind`
   version Spring Boot 4.1.1 pulls in) **does not honour `@JsonIgnoreProperties(ignoreUnknown =
   false)`** placed on a Java record — an annotation that works as expected on a Jackson-2-style
   POJO. The practical effect: every request-model record in this module carries
   `@JsonIgnoreProperties(ignoreUnknown = false)` (see `HearingResultedRequest` and its nested
   records), and every one of them was **silently accepting properties the OpenAPI contract
   forbids** — because the annotation the code relied on to reject them does nothing under
   Jackson 3's record path. This was confirmed empirically, not by reading Jackson's changelog:
   the annotation is present, compiles, and is simply not consulted.
2. **Jackson 2 annotations are still honoured by the Jackson 3 HTTP stack** for the fields and
   inclusion rules that matter here — `@JsonProperty` and `@JsonInclude` on the response records
   behave as expected when Spring serialises them for the HTTP response. Only one JSON message
   converter is registered for the application (Spring Boot 4's Jackson 3 one), so there is no
   ambiguity about which converter handles a given request or response; Jackson 2 is used
   internally by this module's own code, not registered as a competing HTTP converter. The two
   generations coexist safely in that specific sense: no case was found where they disagree about
   how to (de)serialise the same field.

Every schema in the bundled OpenAPI contract is `additionalProperties: false` — the whole point of
the catalogue-and-typed-record architecture is that an undeclared property cannot be emitted or
silently accepted. The unknown-properties gap above defeated that intent on the request-parsing
side.

## Decision

Set `spring.jackson.deserialization.fail-on-unknown-properties: true` globally, in
`application.yaml`. This is the fix that actually holds, because it operates at the
`ObjectMapper`/`JsonMapper`-configuration level Spring Boot controls for the registered HTTP
converter, rather than depending on a per-type annotation that Jackson 3's record path ignores.

This is judged correct, not merely expedient, because every schema in the bundled contract is
closed-world (`additionalProperties: false` throughout) — there is no case in this contract where
accepting an unknown property is the right behaviour.

The per-record `@JsonIgnoreProperties(ignoreUnknown = false)` annotations are left in place. They
are inert under Jackson 3's current record handling, but they are accurate documentation of
intent, they cost nothing, and removing them would suggest (incorrectly) that unknown properties
are meant to be tolerated on those types.

## Consequences

- Every endpoint now genuinely rejects a request carrying a property outside what its schema
  declares, matching the contract's `additionalProperties: false` throughout — this was **not**
  true before this setting was added, despite the per-record annotations suggesting it was.
- **Known limitation, stated plainly:** request fields modelled as `Map<String, Object>` —
  `defendantDetails`, `employerDetails`, `parentGuardianDetails`, `paymentTerms`, `enforcement`,
  and the other opaque request objects the simulator doesn't need to read (see
  `HearingResultedRequest`) — bypass unknown-property enforcement **regardless of this setting**.
  `fail-on-unknown-properties` governs deserialisation into a typed class; a `Map` accepts
  whatever keys are present by construction. `additionalProperties: false` is therefore **not**
  enforced *inside* those objects. This is a real, current gap, not a hypothetical one — it exists
  because those objects are intentionally left opaque (the simulator has no need to read into
  them), and closing it would mean modelling them as typed records purely to gain validation the
  simulator doesn't otherwise need.
- Anyone adding a new request or response type to this module should assume Jackson 3 semantics
  for HTTP (de)serialisation and Jackson 2 semantics only where this module's own code explicitly
  constructs a Jackson 2 `ObjectMapper` (catalogue loading, response-tree assembly). Mixing
  Jackson 2 and Jackson 3 types on the same class is not supported and was not attempted.
