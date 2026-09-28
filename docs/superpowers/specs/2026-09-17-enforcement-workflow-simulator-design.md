# GOB (Libra) Simulator — hearingResultRequest response data

**Jira:** CIMD-4372 (4A), epic "4. Post hearing results (outcome) back to enforcement" (CIMD-2862)
**Date:** 2026-09-17
**Status:** Design approved — pending spec review
**Repo:** `service-cp-crime-results-enforcementworkflow`

---

## 1. Purpose

Provide a GOB (Libra) simulator so CP dev and QA can exercise the enforcement hearing-result flow
end-to-end in **non-live environments** without waiting for the real Libra Gateway integration to be
built. The simulator answers `POST /hearing/result` with a `HearingResultedResponse` whose
`nowsDataItems` are populated according to the posted result codes, so CP can render NOWs.

**Source of truth for the contract:** `libra-gateway-hearing-events-openapi-v0.3.0.yml`.

---

## 2. Scope

### In scope

- `POST /auth/token` — issues an opaque bearer token, recorded with its expiry.
- **Bearer token enforcement** on both hearing endpoints: a token this simulator issued and
  has not expired, or 401. Credentials on `/auth/token` itself are still unchecked.
  Superseded the original deferral — see
  [ADR-004](../../pipeline/adrs/004-enforce-issued-bearer-tokens.md).
- `POST /hearing` — accepts a `HearingConfirmedRequest`, returns `200` with no body.
- `POST /hearing/result` — accepts a `HearingResultedRequest`, returns `200` with a
  `HearingResultedResponse` assembled from the result-code catalogue.
- Per-`caseUrn` seeded stub data, with deterministic defaults when unseeded.
- Deployment to non-live environments only, enforced by build separation and a runtime guard.

### Out of scope

- **Security beyond bearer-token enforcement.** No TLS/mTLS, no WS-Security equivalent, and no
  client-credential checking on `/auth/token`. Token *validation* was originally deferred here too;
  ADR-004 reversed that and moved it into scope above.
- **`enforcerCode`.** Sourced by CP from CP reference data (CIMD-4336). v0.3.0's response schema
  contains no `enforcerCode` property at any level, so the simulator structurally cannot emit one.
- **NOWs generation** and **email notifications** to Enforcement and Confiscation.
- Being a GOB replica. This is a stub with a contract-shaped surface.

---

## 3. Decision: v0.3.0 schema over the Jira examples

The ticket's example responses and its AC2/AC3 entity names **do not validate** against the OpenAPI
spec they claim to be generated from. `NowsDataItems` declares `additionalProperties: false`, and six
of the twelve camelCased request names are not properties of it at all:

| Request name → camelCase | Present in v0.3.0 `NowsDataItems`? |
|---|---|
| `defendantAccount` | No — schema has `defendant` |
| `accountHistory` | No — schema has `paymentHistory` / `transactionHistory` |
| `accountOffencesAndPenalties` | No — schema has `offences` |
| `accountTermsToPay` | No — schema has `terms` |
| `ctAccountBankDetails` | No — schema has `ctBankDetails` |
| `daysBeforeReleaseOfWarrant` | No — schema has `daysBeforeReleaseWarrant` |
| `accountBalance` | Yes, but typed `number`; ticket shows `{totalBalance, balanceOutstanding, …}` |
| `accountWarrantNumber` | Yes, but typed `string` `^\d{3}/\d{2}/\d{5}$`; ticket shows `{warrantNo: "W000012345"}` |
| `accountBailAmount` | Yes, but typed `number`; ticket shows `{bailAmount: 500.00}` |
| `warrantContactDetails` | Yes, but fields are `warrantContactDetailsLine1..5`; ticket shows `processServerName` / `contractorName` / `contractorAddress` |
| `accountNumber` | Yes (shape still differs) |
| `accountDateImposed` | Yes (shape still differs) |

**Resolution: the YAML wins.** The simulator emits only what v0.3.0 declares. AC2's "camelCase the
request name" rule is re-expressed as an explicit *request name → schema property* mapping
(§6.1). This is recorded as ADR-002, and the divergence is raised back to the GOB integration team
as a design-gap artifact so the ticket or a v0.4.0 spec can be corrected.

Rationale: CP will eventually integrate against real Libra, which will serve the published contract.
A simulator that matches the ticket's examples would train CP against a shape that cannot exist.

---

## 4. Architecture

### 4.1 Module layout

The simulator is a **separate Gradle subproject producing its own boot jar**. The root project
remains the service, unchanged.

```
settings.gradle                     # NEW — rootProject.name + include ':enforcement-workflow-simulator'
build.gradle                        # unchanged (the service)
src/                                # unchanged (the service)
gradle/                             # template conventions (three need parameterising, §4.2)
enforcement-workflow-simulator/
├── build.gradle                    # own Spring Boot application + own bootJar
└── src/
    ├── main/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/
    │   ├── EnforcementWorkflowSimulatorApplication.java
    │   ├── guard/LiveEnvironmentGuard.java
    │   ├── api/            # controllers + request/response records
    │   ├── catalogue/      # catalogue loading + startup validation
    │   ├── assembly/       # the response-assembly engine
    │   └── seed/           # seed store + deterministic defaults
    ├── main/resources/
    │   ├── application.yaml
    │   ├── openapi/libra-gateway-hearing-events-v0.3.0.yml
    │   ├── enforcement-workflow-simulator/catalogue/{entity-names,field-paths,result-codes}.yaml
    │   └── enforcement-workflow-simulator/seeds/<caseUrn>.json
    └── test/java/uk/gov/hmcts/cp/enforcementworkflowsimulator/...
```

**Why root-as-service rather than `:app` + `:enforcement-workflow-simulator`:** splitting out an `:app` subproject
would relocate `src/`, `Dockerfile`, `docker-compose.yml` and every CI path reference, producing a
large diff against a repo freshly scaffolded from `service-hmcts-crime-springboot-template`. It
yields no isolation benefit — separate boot jar and "nothing deploys it" hold identically with one
subproject.

### 4.2 Template convention fixes

Three `gradle/*.gradle` files assume a single project. Each is **parameterised, not forked**, so the
repo stays diffable against the HMCTS template:

| File | Problem | Fix |
|---|---|---|
| `jar.gradle` | `archiveFileName = "${rootProject.name}-${project.version}.jar"` — the simulator jar would be named after the service | use `project.name` |
| `pmd.gradle` | `files(".github/pmd-ruleset.xml")` resolves relative to the subproject, where it does not exist | `rootProject.file(".github/pmd-ruleset.xml")` |
| `repositories.gradle` | applies `publishing {}` to whatever project includes it — would publish the simulator to Azure Artifacts and GitHub Packages | applied from the root `build.gradle` only |

This is a deviation from the template (which assumes one module) and is recorded as ADR-001.

### 4.3 Live-environment guard

Three independent layers. Layers 1 and 2 are the design; layer 3 is defence in depth.

1. **Nothing packages it.** The root `Dockerfile` adds only the service boot jar. No workflow in
   `.github/workflows` and no ADO mirror stage builds or pushes a simulator image. The
   `:enforcement-workflow-simulator` subproject is excluded from `publishing`.
2. **Allow-list.** `EnforcementWorkflowSimulatorApplication` refuses to start unless the `enforcement-workflow-simulator` Spring
   profile is active. Absence of the profile means no endpoints exist.
3. **Deny-list fail-fast.** `LiveEnvironmentGuard` implements
   `ApplicationListener<ApplicationEnvironmentPreparedEvent>` — which fires *before any bean is
   created*, earlier than `@PostConstruct` — and throws if the active profile set intersects
   `{prod, production, live, perf, preprod}`. Throwing prevents context startup, so a misdeployed
   pod crash-loops visibly rather than quietly serving fabricated court data.

The existing SOAP stub (`stagingenforcement-gob-test-server`) relies on layer 1 alone, with no
runtime check — reviewed and deliberately improved on here.

---

## 5. Data flow

```
POST /hearing/result
        │
        ├─ results[].resultCode  (1..n) ──► result-codes.yaml ──► union of required field labels
        │                                                          (deduplicated across codes)
        ├─ nowsDataRequest                ──► entity-names.yaml ──► set of requested schema properties
        │   .nowsDataItems[].name (1..12)
        │
        └─ caseUrn ──► seed store ──► seed file, else deterministic defaults
                                              │
                                              ▼
        field-paths.yaml maps each required label to a v0.3.0 JSON path.
        Labels whose root property is not requested are discarded.
        Requested properties with no contributing label are still emitted,
        populated from defaults (AC2: no missing keys).
                                              │
                                              ▼
                              HearingResultedResponse
                              { caseUrn, timestamp, correlationId?, nowsDataItems }
```

---

## 6. The catalogue

Three YAML resources hold all domain knowledge; the Java engine is thin and generic. The catalogue
is a line-by-line mirror of the ticket's "Result vs Required Field" table so a reviewer can diff it
against Jira without reading Java, and so table revisions are data edits rather than code changes.

### 6.1 `entity-names.yaml` — the twelve request names

| `NowsDataItemName` | v0.3.0 property | Type |
|---|---|---|
| `Defendant Account` | `defendant` | object |
| `Account History` | `paymentHistory` **and** `transactionHistory` | object (see OQ-1) |
| `Account Offences and Penalties` | `offences` | object |
| `Account Terms to Pay` | `terms` | object |
| `Account Balance` | `accountBalance` | number |
| `Account Warrant Number` | `accountWarrantNumber` | string, `^\d{3}/\d{2}/\d{5}$` |
| `Warrant Contact Details` | `warrantContactDetails` | object |
| `Account Bail Amount` | `accountBailAmount` | number |
| `CT Account Bank Details` | `ctBankDetails` | object |
| `Days Before Release of Warrant` | `daysBeforeReleaseWarrant` | integer |
| `Account Number` | `accountNumber` | string |
| `Account Date Imposed` | `accountDateImposed` | string, `DD Mon YYYY` |

### 6.2 `field-paths.yaml` — field label → JSON path

Needed because v0.3.0 types several entities as **scalars**, so a table row's fields cannot all live
inside the entity that row names. "Total Balance" is `accountBalance` (the whole entity), but
"Balance Outstanding" has to land on `offences.accountTotal`.

| Table field label | v0.3.0 path | Type |
|---|---|---|
| Account No. | `accountNumber` | string |
| Total Balance | `accountBalance` | number |
| Balance Outstanding | `offences.accountTotal` | number |
| Amount Paid or Cancelled | `offences.accountPaid` | number |
| Amount Imposed | `offences.offence[].impositions.imposition[].amountImposed` | number |
| Imposition type | `offences.offence[].impositions.imposition[].impositionType` | string |
| Date Imposed | `accountDateImposed`, `offences.offence[].dateImposed` | string |
| Time of Offence | `offences.offence[].timeOfOffence` | string |
| Place of offence | `offences.offence[].placeOfOffence` | string |
| Ticket No. | `offences.offence[].ticketNumber` | string |
| Vehicle Reg. No. | `offences.offence[].vehicleReg` | string |
| Vehicle Make | `defendant.assetVehicleMake` | string |
| NTO issued on `<date>` & `<time>` | `offences.offence[].noticeToOwnerNoticeToHirer` + `.dateIssued` | string |
| Payment Terms | `terms.english_due` | string |
| Due Date | `terms.english_firstDate` | string |
| Instalment Amount | `terms.english_instalment` | string |
| Lump Sum | `terms.english_lumpsum` | string |
| Payment period | `terms.english_instalmetPaymentPeriod` | string (typo is the spec's) |
| Warrant No | `accountWarrantNumber` | string |
| Bail Amount | `accountBailAmount` | number |
| Days Before Release of Warrant | `daysBeforeReleaseWarrant` | integer |
| Clamping Contractor name | `warrantContactDetails.warrantContactDetailsLine1` | string |
| Contractor's Name | `warrantContactDetails.warrantContactDetailsLine2` | string |
| Contractor's Address | `warrantContactDetails.warrantContactDetailsLine3..5` | string |
| Process Server Name | `warrantContactDetails.warrantContactDetailsLine1` | string |
| Parent Name and Address | `defendant.parentGuardian.parentGuardianName` + `.parentGuardianAddress.*` | object |
| `<Notes 1..3>` | `defendant.accountNotes.accountNote1..3` | string |

**Labels with no v0.3.0 home** — carried in the catalogue as `unmapped: true` with a reason, emitted
nowhere, and raised as open questions (OQ-4):

| Label | Nearest property | Why it does not fit |
|---|---|---|
| Date of Offence | `offences.offence[].dateIssued` | `dateIssued` is the NTO issue date, not the offence date |
| Start time of offence | `offences.offence[].timeOfOffence` | schema has one time field, not a range |
| End time of offence | — | no property |
| Reserve Terms | — | no property |
| Reason for decision | `defendant.accountNotes.accountNote1` | notes are free text, not a decision reason |
| `[Directions]` | — | no property; bracketed as optional in the table |

### 6.3 `result-codes.yaml` — the table

Twenty rows map directly. Aliases and gaps are explicit rather than implied:

```yaml
ABDC:  ["Account No.", "Total Balance", "Balance Outstanding"]
AEO:   ["Account No.", "Total Balance", "Payment Terms", "Due Date",
        "Instalment Amount", "Payment period", "Reserve Terms"]
# … 18 more rows transcribed from the CIMD-4372 table

# GOB/CP synonyms — the enum carries both spellings of the same outcome
DW:    { alias: WC }        # DW (GOB) = WC (CP)
TFOUT: { alias: TFOOUT }    # TFOOUT (GOB) = TFOUT (CP)
WWDN:  { alias: WDN }       # WDN (GOB) = WWDN (CP)

# Postable per the enum, but CIMD-4372 defines no required fields (OQ-2)
NOENF: { fields: [], note: "No row in CIMD-4372 Result vs Required Field table" }
WDN:   { fields: [], note: "No row in CIMD-4372 Result vs Required Field table" }
```

### 6.4 Result-code coverage

Cross-checking the v0.3.0 `resultCode` enum (24 values, 21 distinct outcomes after aliasing) against
the ticket's table (30 short codes):

- **20 codes map directly**, plus `DW` via the table's "WC (DW in GoB)" row = **21 covered**.
- **3 postable codes have no table row:** `NOENF`, `WDN`, `WWDN` (OQ-2).
- **10 table rows can never fire** — `ACF`, `AEC`, `CLAMPS`, `FIDIC`, `FIDICT`, `FTTP`, `LATG`,
  `LATR`, `PGPAY`, `PTNV` are absent from the enum, so CP cannot post them (OQ-3). They are
  transcribed into the catalogue anyway and marked `postable: false`, ready for a future spec
  version, and a unit test asserts they stay unreachable.

### 6.5 Startup validation

The context fails to start if any of these hold. The catalogue cannot silently rot:

- a `result-codes.yaml` field label is absent from `field-paths.yaml`;
- a `field-paths.yaml` path does not resolve against the bundled OpenAPI schema — entries marked
  `unmapped: true` carry no path and are exempt from this check, but must carry a reason;
- an `entity-names.yaml` target is not a property of `NowsDataItems`;
- an alias points at an undefined code, or a cycle exists;
- a `resultCode` enum value is neither mapped, aliased, nor explicitly declared a known gap.

---

## 7. Assembly engine

```
requestedProps  = request.nowsDataRequest.nowsDataItems[].name
                     .map(entityNames::lookup)                     # AC2
requiredLabels  = request.results[].resultCode
                     .map(resultCodes::resolveAlias)
                     .flatMap(resultCodes::fields)
                     .distinct()                                   # AC3 — union, deduplicated
selected        = requiredLabels
                     .filter(label -> rootOf(fieldPaths[label]) in requestedProps)
                     .filter(label -> !fieldPaths[label].unmapped)
values          = seedStore.find(caseUrn).orElse(deterministicDefaults)  # AC8
```

**Emission rule not stated in the ACs but forced by the schema:** every requested property is
emitted, even when the posted codes contribute no field to it. AC2 requires no missing keys, and an
empty `{}` would fail validation for entities with schema-required fields (`Defendant.defName`,
`Offences.accountTotal`, `Imposition.amountImposed`). Such properties are therefore populated from
defaults, guaranteeing at minimum their required fields.

Response envelope:

- `caseUrn` — echoed from the request.
- `timestamp` — server-generated ISO-8601 UTC, e.g. `2026-05-03T14:30:00Z` (AC6).
- `correlationId` — echoed from `X-Correlation-ID` when present; **omitted** otherwise (AC6).

**Idempotency (AC7):** two requests carrying the same `X-Idempotency-Key` return a byte-identical
body, `timestamp` included; a different key yields a fresh timestamp. The response is cached against
the key in a bounded in-memory store (size-capped with TTL eviction; state is not required to
survive restart). `X-Idempotency-Key` is **not declared in v0.3.0** — implemented as an undeclared
request header, which OpenAPI does not forbid, and raised as OQ-6.

---

## 8. Seeding and defaults

Seeds are `enforcement-workflow-simulator/seeds/<caseUrn>.json` on the classpath, loaded at startup, with the
directory overridable via `ENFORCEMENT_WORKFLOW_SIMULATOR_SEED_DIR` for environment-specific data. Chosen over a
runtime admin endpoint because seeds are then versioned in git, reproducible in CI, and survive pod
restarts — and because an admin endpoint would add surface outside the contract. The ticket's 21
worked examples become the initial seed files.

Unseeded case URNs resolve to deterministic defaults so smoke tests are stable without seeding
(AC8). Defaults follow the schema, not the ticket, where the two disagree:

| Field | Default | Note |
|---|---|---|
| Account No. | `ACC0001` | as AC8 |
| Total Balance | `1250.00` | as AC8 |
| Balance Outstanding | `875.50` | as AC8 |
| Amount Paid or Cancelled | `375.00` | as AC8 |
| Bail Amount | `500.00` | |
| Days Before Release of Warrant | `14` | |
| Warrant No | `012/26/00123` | **not** AC8's `W000012345` — see OQ-4 |
| Date Imposed | `15 Jan 2026` | **not** AC8's `2026-01-15` — see OQ-5 |

No default contains real PII, case data, or court reference numbers.

---

## 9. Endpoints

| Endpoint | Behaviour |
|---|---|
| `POST /auth/token` | Accepts `application/x-www-form-urlencoded` `OAuthTokenRequest`. Returns `OAuthTokenResponse` with an opaque `access_token`, `token_type: Bearer`, `expires_in: 3600`. Credentials are not checked, but the token is recorded and IS validated on the hearing endpoints (ADR-004). |
| `POST /hearing` | Requires `Authorization: Bearer <issued token>`. Accepts `HearingConfirmedRequest`. Returns `200`, no body. |
| `POST /hearing/result` | Requires `Authorization: Bearer <issued token>`. Accepts `HearingResultedRequest`. Returns `200` with a `HearingResultedResponse` per §7. |

Request validation is schema-driven: a body violating `HearingResultedRequest` (including
`additionalProperties: false`) returns `400` with the spec's `ErrorResponse`. `401` is produced for a missing, unissued or expired
bearer token (ADR-004). Of the remaining spec-declared status codes, `403` is not produced — the
scheme declares no scopes — and `404` is not produced for a case URN, since every URN resolves to
seeded or default data.

---

## 10. Response model

Hand-written Java records with `@JsonProperty`, **not** generated. The spec mixes naming
conventions — `english_due`, `ct_account_number`, `DoB` — which openapi-generator mangles without
careful per-field configuration, and adding a codegen plugin is a further template deviation.

Conformance is instead guaranteed by test: a schema-conformance integration test validates every
response against the bundled `libra-gateway-hearing-events-v0.3.0.yml` using
`swagger-request-validator`. That test is the real AC1 guarantee and catches drift regardless of how
the records were produced — it would be needed even with generation.

Records are serialised with `@JsonInclude(NON_NULL)` so unrequested properties are absent rather
than `null`, satisfying AC2's "no extra keys".

Monetary fields are held as `BigDecimal` with scale 2 and serialised without quoting, so AC4's "two
decimal places" survives (`1250.00`, not `1250.0`) while the value stays a JSON `number` as the
schema requires. See OQ-9.

---

## 11. Testing

Per the repo's hard rule — at least one integration test per new endpoint, suite green locally.

**Integration**

- One IT per endpoint: `/auth/token`, `/hearing`, `/hearing/result`.
- **Auth:** 401 for an absent header, a non-`Bearer` scheme, and a well-formed token the
  simulator never issued; expiry covered at unit level with an offset `Clock`.
- **Schema conformance:** post each of the 21 distinct result codes and validate every response
  against the bundled OpenAPI document (AC1, AC3, AC4).
- **Entity-key exactness:** requested names ↔ response keys, no extras, no omissions (AC2).
- **Correlation:** echoed when `X-Correlation-ID` is present, omitted when absent (AC6).
- **Idempotency:** identical key → byte-identical body including `timestamp`; different key → fresh
  timestamp (AC7).
- **Seeding:** a seeded case URN returns seeded values; an unseeded one returns AC8 defaults.
- **`enforcerCode` absence:** assert the string appears nowhere in any response (AC5).

**Unit**

- Engine: union across multiple codes, deduplication, alias resolution, filtering to requested
  entities, the always-emit rule of §7.
- Catalogue validation: each failure mode of §6.5 fails startup.
- Unreachable rows: the 10 non-enum codes stay unpostable.
- `LiveEnvironmentGuard`: context startup fails under each forbidden profile and succeeds under
  `enforcement-workflow-simulator`.

**Build**: `./gradlew :enforcement-workflow-simulator:test` plus the root build, both green before review.

---

## 12. Open questions

Carried into the design-gap artifact and raised with the GOB integration team. None is invented or
assumed away; each has a stated interim behaviour so implementation is not blocked.

| # | Question | Interim behaviour |
|---|---|---|
| OQ-1 | `Account History` → `paymentHistory`, `transactionHistory`, or both? | Emit both; flag for confirmation |
| OQ-2 | `NOENF`, `WDN`, `WWDN` are postable but have no table row | Contribute no fields; requested entities return defaults |
| OQ-3 | 10 table rows are absent from the `resultCode` enum — dead until reference data lands? | Transcribed, marked `postable: false`, asserted unreachable |
| OQ-4 | Warrant No: AC4 says `A(10)` (`W000012345`), schema says `^\d{3}/\d{2}/\d{5}$` | Schema wins — `012/26/00123` |
| OQ-5 | Date Imposed: AC4 says `YYYY-MM-DD`, schema says `x-date-format: DD Mon YYYY` | Schema wins — `15 Jan 2026` |
| OQ-6 | `X-Idempotency-Key` (AC7) is undeclared in v0.3.0 | Undeclared request header; propose for v0.4.0 |
| OQ-7 | Six table field labels have no v0.3.0 property (§6.2) | Marked `unmapped`, emitted nowhere |
| OQ-8 | Ticket examples are non-conformant to the spec they cite | Spec wins (§3); ticket or v0.4.0 to be corrected |
| OQ-9 | AC4 requires amounts "numeric with 2 decimal places", but the schema types them as JSON `number`, which carries no scale — `1250.00` serialises as `1250.0` | Serialise via `BigDecimal` with scale 2 to preserve the two decimal places while remaining a JSON number; confirm CP's parser accepts it |

---

## 13. Artefacts

| Artefact | Path |
|---|---|
| Design-gap HTML artifact — v0.3.0 contract conflicts | `docs/pipeline/artifacts/001-libra-v030-contract-gaps.html` |
| Implementation-plan HTML artifact — **mandatory before Stage 5** | `docs/pipeline/artifacts/002-enforcement-workflow-simulator-implementation-plan.html` |
| ADR-001 — simulator as a Gradle subproject in the service repo (template deviation) | `docs/pipeline/adrs/001-enforcement-workflow-simulator-subproject.md` |
| ADR-002 — v0.3.0 schema chosen over the CIMD-4372 examples | `docs/pipeline/adrs/002-v030-schema-over-jira-examples.md` |
| This spec | `docs/superpowers/specs/2026-09-17-enforcement-workflow-simulator-design.md` |

---

## 14. Acceptance criteria traceability

| AC | Where satisfied |
|---|---|
| AC1 — 200 + schema-valid body, unknown keys rejected | §10 records + §11 schema-conformance IT |
| AC2 — response keys equal requested names, 1:1 | §6.1 mapping (re-expressed per §3) + §7 always-emit rule |
| AC3 — fields match required columns, union across codes, deduplicated | §6.3 catalogue + §7 engine |
| AC4 — declared value formats | §6.2 types + §8 defaults; OQ-4/OQ-5 resolved to the schema |
| AC5 — no `enforcerCode` anywhere | Structural: v0.3.0 declares no such property; asserted in §11 |
| AC6 — `correlationId` echo/omit, server `timestamp` | §7 response envelope |
| AC7 — idempotency by key | §7 idempotency cache; OQ-6 on the undeclared header |
| AC8 — deterministic defaults when unseeded | §8 |

---

## 15. Changes since the original spec

This section is added, not rewritten in place, so the design history above stays intact. It
records where the built system diverges from what §§1-14 describe, following the whole-branch
review that found this document stale at the Stage 6 human gate.

**Contract version.** The bundled contract is now
`libra-gateway-hearing-events-v0.4.0.yml`, not v0.3.0. The upgrade (commit `6d3c85d`) changed
only the `resultCode` enum (24 → 42 values); `NowsDataItems` and its nested schemas are
byte-identical to v0.3.0, so §§4, 6.1, 6.2, 7, 10 remain accurate in substance — only the filename
and enum-count figures below are stale. `CatalogueLoader.OPENAPI_SPEC_PATH` and
`OpenApiConformance.SPEC_PATH` both point at the v0.4.0 file.

**§6.4 Result-code coverage is materially wrong for v0.4.0** and should be read as follows
instead: the bundled enum now has **42** `resultCode` values, not 24/21. Of those, **17** are
`fields: []` gap rows in `result-codes.yaml` (no CIMD-4372 mapping — 15 are new-in-v0.4.0 codes
awaiting a vendor mapping, 2 — `NOENF`/`WDN` — are old codes the CIMD-4372 table never covered),
and **7** table rows are marked `postable: false` because they are absent from the v0.4.0 enum
(down from the 10 §6.4 lists — v0.4.0 made `ACF`, `AEC`, `FIDIC`, `FIDICT`, `FTTP`, `PTNV`
postable, resolving that part of OQ-3; the remaining `postable: false` rows are `WC`, `TFOUT`,
`WWDN`, `CLAMPS`, `LATG`, `LATR`, `PGPAY` — see `result-codes.yaml` for the authoritative list).
`AllResultCodesConformanceIT` posts and validates all 42 codes, not 21 — see §11 below.

**§11 "post each of the 21 distinct result codes"** is stale for the same reason: the suite posts
all **42** result codes in the bundled enum and asserts schema conformance for each
(`AllResultCodesConformanceIT`), plus the entity-shrink invariant added in the whole-branch review
(`NowsDataItemsAssemblerTest#posting_an_additional_fields_empty_code_never_shrinks_an_entity`).

**§12 OQ-3 is partly resolved.** v0.4.0 made `ACF`, `AEC`, `FIDIC`, `FIDICT`, `FTTP`, and `PTNV`
postable (they moved out of the `postable: false` set into normal mapped/gap rows). The remaining
`postable: false` rows are asserted unreachable by `CatalogueCoverageTest`, unchanged in spirit
from the original design.

**§13 Artefacts table.** ADR-002 was renamed from `002-v030-schema-over-jira-examples.md` to
`docs/pipeline/adrs/002-contract-over-ticket-examples.md` when it stopped being v0.3.0-specific.
A third ADR was added and is not listed in §13: `docs/pipeline/adrs/003-jackson-generations.md`,
recording why Jackson 2 (catalogue loading, response-tree assembly) and Jackson 3 (Spring Boot 4.1's
HTTP message conversion) deliberately coexist in this module, and the two behavioural gaps that
forced `@JsonIgnoreProperties`/`additionalProperties: false` to be enforced by hand rather than
relying on the annotation Jackson 3's record deserialiser does not honour.

**§6.5 bullet 2 overstates what happens at startup.** It reads as though every field-path is
resolved against the live filesystem/seed data at startup; in the built system, startup validation
checks each `field-paths.yaml` path's **root property** against the OpenAPI schema's declared
`NowsDataItems` properties (`CatalogueLoader.validate()`) — it does not walk the full nested path,
and it does not touch `SeedStore` or `ENFORCEMENT_WORKFLOW_SIMULATOR_SEED_DIR` at all. A path whose root is valid
but whose nested structure is wrong (e.g. a typo'd leaf property) is caught later, not at startup:
either by `NowsDataItemsAssembler`'s conversion to the typed `NowsDataItems` record (Jackson
rejects an undeclared property), exercised by the integration test suite, or — for `SeedStore`'s
own `ENFORCEMENT_WORKFLOW_SIMULATOR_SEED_DIR` handling specifically — only by the tests added under Finding I7 of
the whole-branch review (see `SeedStoreTest`); there is no startup-time check that the environment
variable, if set, actually points at a readable directory.

**Also added since this spec, not described above:**
- `IdempotencyCache` binds a cached response to the *request* as well as the key (a same-key,
  different-body request is a cache miss, never a cross-case replay) — a safe strengthening of
  AC7, not a contract change. See the `IdempotencyCache` javadoc and `IdempotencyCacheTest`.
- `GlobalExceptionHandler` maps framework `NoResourceFoundException` (404),
  `HttpRequestMethodNotSupportedException` (405), and `HttpMediaTypeNotSupportedException` (415)
  to contract-shaped `ErrorResponse` bodies at their real status codes, rather than letting the
  broad `Exception.class` catch-all turn them into 500s.
- `field-paths.yaml` documents an explicit, deliberate tie-break where two rows target the same
  JSON path (`warrantContactDetails.warrantContactDetailsLine1`), and `CatalogueLoader` preserves
  catalogue file order deterministically (not `Map.copyOf`'s per-JVM-randomised order) so that
  tie-break is stable across restarts.
- **`GlobalExceptionHandler` returns 405 and 415 for their real conditions, neither of which is a
  declared response for `/hearing` or `/hearing/result`.** The bundled contract's responses for
  both operations are 400/401/403/404/500 only — 405 (unsupported HTTP method) and 415
  (unsupported `Content-Type`) are not among them. Finding I6's fix maps Spring's own
  `HttpRequestMethodNotSupportedException`/`HttpMediaTypeNotSupportedException` to their true
  status with a contract-shaped `ErrorResponse` body, rather than letting the
  `@ExceptionHandler(Exception.class)` catch-all turn them into 500s. This is an accepted,
  deliberate deviation from AC1's "schema-valid body" read narrowly as "declared status code": the
  response *body* still conforms to `ErrorResponse`, and a true 405/415 is judged more useful to a
  caller than a 500 masking a routine mistake. See `GlobalExceptionHandlerIT` and the simulator
  README's "Known gaps" section.
- **A requested-but-untouched object-typed entity is populated from its `baseline: true` rows
  only, and is never emitted as `{}`.** Following on from Finding I4 (§ above, `defaultFor()`
  unions only baseline rows for an object-typed root that no posted code touched), two
  object-typed entities — `terms` and `warrantContactDetails` — had zero baseline rows and so came
  back as `{}` in that case. Both now carry one representative baseline row each (`Payment Terms`
  for `terms`; `Clamping Contractor name`, the pre-existing I3 tie-break's deliberate winner, for
  `warrantContactDetails`), flagged solely to give those two entities a non-empty floor — neither
  `Terms` nor `WarrantContactDetails` declares a `required:` list in the contract, so this is not a
  schema-required backfill in the sense §"6.2" and the `baseline: true` convention otherwise mean;
  it is a second, independent reason the flag exists (see the simulator README's "`baseline: true`
  in `field-paths.yaml`" section). No other row was flagged for this: doing so for every row would
  make every requested entity fully populated regardless of which result codes were posted,
  destroying the simulator's ability to differentiate result codes by field content — see
  `NowsDataItemsAssemblerTest#a_requested_but_untouched_object_typed_entity_is_never_empty` and
  `#unions_required_fields_across_several_posted_codes`.
