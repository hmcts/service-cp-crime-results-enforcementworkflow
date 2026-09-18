# GOB (Libra) simulator

A non-live simulator of three endpoints of the vendor Libra Gateway hearing-event API
(`POST /auth/token`, `POST /hearing`, `POST /hearing/result`), so CP can exercise the enforcement
hearing-result flow without waiting for the real Libra integration.

## What it is not

- **Not a security implementation.** `/auth/token` returns a fixed bearer token and validates
  nothing about the request. No endpoint checks an `Authorization` header. 401/403/404 are
  unreachable — see "Known gaps" below.
- **Not a shape the real Libra necessarily returns.** It follows the **published OpenAPI
  contract** bundled at
  [`src/main/resources/openapi/libra-gateway-hearing-events-v0.4.0.yml`](src/main/resources/openapi/libra-gateway-hearing-events-v0.4.0.yml)
  — **not** the CIMD-4372 ticket's example response bodies, which do not validate against that
  contract. See [ADR-002](../docs/pipeline/adrs/002-contract-over-ticket-examples.md) for why, and
  [pipeline artifact 001](../docs/pipeline/artifacts/001-libra-v030-contract-gaps.html) for the
  full gap analysis. If you are building against this simulator expecting the ticket's nested
  shapes (e.g. `accountWarrantNumber` as an object), you will be surprised — the contract wins.
- **Not part of the service artefact.** It builds and ships as its own boot jar. It is never
  published and never copied into the root `Dockerfile`'s image. See
  [ADR-001](../docs/pipeline/adrs/001-gob-simulator-subproject.md).
- **Not deployable to a live environment**, by design — see "Guard layers" below.

## How to run it

```bash
SPRING_PROFILES_ACTIVE=gob-simulator ./gradlew :gob-simulator:bootRun
```

Listens on port `8091` (override with `SERVER_PORT`). The `gob-simulator` profile is the default
if `SPRING_PROFILES_ACTIVE` is unset (see `src/main/resources/application.yaml`), but the guard
below requires it to be present explicitly among the active profiles in any environment that sets
its own profile list.

## Guard layers — why it cannot start in a live environment

Three independent layers, so a single mistake in any one of them does not put fabricated court
data into a real environment:

1. **Nothing packages it.** The root `Dockerfile` is `COPY build/libs/*.jar`, which is
   root-project-relative. `:gob-simulator`'s boot jar lands in `gob-simulator/build/libs/`, which
   that `COPY` cannot reach. The simulator has no path into the service's container image.
2. **A profile allow-list.** `application.yaml` only activates the simulator's beans under the
   `gob-simulator` Spring profile.
3. **A pre-bean fail-fast.** `LiveEnvironmentGuard` listens for
   `ApplicationEnvironmentPreparedEvent` — which fires before any bean is created, earlier than
   `@PostConstruct` — and throws `IllegalStateException` if any of `prod`, `production`, `live`,
   `perf`, or `preprod` is an active profile, or if `gob-simulator` is not. A misdeployed pod
   crash-loops visibly rather than serving fabricated data.

See `LiveEnvironmentGuard`, `LiveEnvironmentGuardTest` and `LiveEnvironmentGuardStartupTest`.

## Architecture, briefly

Three YAML catalogue resources under `src/main/resources/gob-simulator/catalogue/` encode the
CIMD-4372 business mapping table as data:

- `entity-names.yaml` — the 12 `NowsDataItemName` request values CP can ask for, mapped to the
  real `NowsDataItems` schema properties they resolve to (not a camelCase transform — see
  ADR-002).
- `field-paths.yaml` — field label → JSON path into the response tree, with type and default
  value.
- `result-codes.yaml` — for each of the 42 `resultCode` enum values, which field labels a posted
  result code requires.

`CatalogueLoader` loads and validates these against each other and against the bundled OpenAPI
contract **at startup**: an entity name pointing at a property the schema doesn't declare, a field
path rooted at an unknown property, an unmapped label with no reason, a result code in the schema
enum missing from the catalogue — any of these aborts the application context. The catalogue must
never silently return a wrong or empty answer.

`NowsDataItemsAssembler` then, per request:

1. Unions the field labels required by every posted `resultCode`.
2. Filters that union to the entities (`NowsDataItemName` values) CP actually requested.
3. Resolves each field's value from a per-case seed (`SeedStore`/`ValueResolver`) or its catalogue
   default.
4. Fills in a requested entity no posted code touched at all. For an object-typed entity (one
   whose field-paths.yaml rows nest further under it, e.g. `offences.accountTotal`) this fills
   only its `baseline: true` rows, not every row mapped to it — see "`baseline: true` in
   `field-paths.yaml`..." below for why. A scalar entity (a single leaf value, e.g.
   `accountBalance`) always gets its one value. Either way, a requested entity is never missing
   from the response — see "the default-content floor" below for why it is also never `{}`.
5. Fills schema-required gaps left by a posted code's own *partial* contribution to an entity,
   without overwriting anything that code already supplied (`baseline: true` — see below).
6. Converts the resulting `Map` tree to `NowsDataItems` via Jackson. A path the contract doesn't
   declare fails this conversion rather than reaching CP — the typed records make an
   undeclared property structurally impossible to emit.

### `baseline: true` in `field-paths.yaml` drives two independent things

This is the single easiest thing to get wrong when editing the catalogue, so it is stated here
plainly. `baseline: true` does **not** mean "this row was added later", and it does **not** mean
"no result code posts this" — those are independent facts that happen to coincide for some rows.
It means one, or both, of:

1. **The OpenAPI schema requires this property** on its entity.
   `NowsDataItemsAssembler.mergeBaselineGaps()` reads the flag to backfill a schema-required field
   that a posted code's own (partial) contribution to an entity left unfilled, without touching
   anything the posted code did supply. Marking a row `baseline: true` for this reason when the
   property is not actually schema-required — or missing the flag on one that is — breaks
   conformance for any result code that only partially populates that entity. If you are unsure
   whether a property is schema-required, check the bundled OpenAPI contract's `required:` list
   for that schema component directly; don't infer it from whether a result code happens to post
   it.
2. **It is the chosen non-empty floor for an object-typed entity with no other baseline row.**
   `defaultFor()` unions only `baseline: true` rows for an object-typed entity that no posted code
   touched at all (see "the default-content floor" below); an entity with zero baseline rows
   would otherwise come back as `{}` in that case even though its schema has no `required` block
   forcing it to. `Payment Terms` (`terms`) and `Clamping Contractor name`
   (`warrantContactDetails`) are flagged for this reason alone — `Terms` and
   `WarrantContactDetails` declare no `required:` list in the contract, so this is not a
   schema-required backfill; it exists purely to keep those two entities from ever being emitted
   empty.

There are **14** `baseline: true` entries in `field-paths.yaml`. Nine of them (`Defendant Name`,
`Offence Date Imposed`, `Offence Code`, `Offence Title`, `Offence Total`, `Imposition Amount Paid`,
`Imposition Balance`, `CT Account Number`, `CT Sort Code`) have no CIMD-4372 result-code row of
their own and exist purely for reason 1. Three more (`Balance Outstanding`,
`Amount Paid or Cancelled`, `Amount Imposed`) **do** have result-code rows in the CIMD-4372 table
**and** are schema-required — both facts are true at once, for unrelated reasons, but the flag is
still there for reason 1. The remaining two (`Payment Terms`, `Clamping Contractor name`) exist
purely for reason 2, as described above.

### The default-content floor: a requested-but-untouched entity is never `{}`

Finding I4 narrowed `defaultFor()` so that, for an object-typed entity, it unions only its
`baseline: true` rows when no posted code touched that entity at all — otherwise posting an
additional `fields: []` gap code on top of a code that only partially populated the same entity
could make it look like adding a result code *removed* fields (see
`NowsDataItemsAssemblerTest#posting_an_additional_fields_empty_code_never_shrinks_an_entity`).
That fix is correct for monotonicity, but it means an object-typed entity with **no** `baseline`
row at all would be emitted as `{}` for a request that never touches it, even where its schema
imposes no `required` block that would forbid `{}`. Two of the five object-typed entities
(`terms`, `warrantContactDetails`) had exactly zero baseline rows, so this was live — a requested
`Account Terms to Pay` or `Warrant Contact Details` with no posted code contributing to it came
back empty. Both now carry one representative `baseline: true` row (`Payment Terms` and
`Clamping Contractor name` respectively — see reason 2 above), so every object-typed entity
(`defendant`, `offences`, `terms`, `warrantContactDetails`, `ctBankDetails`) has at least one
baseline row and none of them is ever emitted empty.

Deliberately **not** extended beyond those two rows: flagging every field-paths.yaml row
`baseline: true` would make every requested entity fully populated regardless of which result
codes were actually posted, destroying the simulator's ability to differentiate result codes by
their field lists. `NowsDataItemsAssemblerTest` asserts this floor directly (a requested,
untouched entity is non-empty for every object-typed root) and separately asserts that two
deliberately non-baseline fields (`Imposition type`, `Place of offence`) stay absent unless their
own result code is posted — see
`NowsDataItemsAssemblerTest#unions_required_fields_across_several_posted_codes`.

`paymentHistory`/`transactionHistory` are unaffected: they have no field-paths.yaml rows at all
(so `NowsDataItemsAssembler#isObjectTyped` does not even class them as object-typed), and their
schema components declare no `required` block, so `{}` already validates for them.

## How to add a seed file

`SeedStore` resolves per-`caseUrn` stub data in two places, external directory first:

1. `${GOB_SIMULATOR_SEED_DIR}/<caseUrn>.json` — an external directory, if the environment variable
   is set and the file exists.
2. `gob-simulator/seeds/<caseUrn>.json` on the classpath — bundled seeds, under
   `src/main/resources/gob-simulator/seeds/`.

A seed file is a JSON object whose keys are dotted/indexed paths matching `field-paths.yaml`'s
`path` values (e.g. `accountNumber`, `offences.accountTotal`,
`offences.offence[0].impositions.imposition[0].amountImposed`), nested as an object tree rather
than flat dotted keys — see `E011122334.json` for a worked example covering account, offence and
defendant fields. Any field the seed doesn't cover falls back to its catalogue default. To add one:

1. Create `src/main/resources/gob-simulator/seeds/<caseUrn>.json` (or drop it in the directory
   named by `GOB_SIMULATOR_SEED_DIR` for a non-bundled environment).
2. Populate only the fields you need to control; everything else defaults.
3. Post a hearing result with that `caseUrn` and check the response.

## How to change the catalogue

When the CIMD-4372 mapping table changes, or the contract moves to a new version:

1. Edit the relevant YAML file(s) under `src/main/resources/gob-simulator/catalogue/`. Add new
   result codes, field labels, or entity mappings as new rows — don't restructure the shape
   without checking `CatalogueLoader`'s parsing of it first.
2. If a field label has no corresponding schema property, mark it `unmapped: true` with a
   `reason:` string — `CatalogueLoader` fails startup if either is missing.
3. If a result code isn't in the schema's `resultCode` enum yet, mark its row `postable: false` so
   `CatalogueCoverageTest` (which asserts every `postable: false` row is genuinely absent from the
   enum) doesn't fail.
4. If the contract version changes, replace the file under `src/main/resources/openapi/` and
   update the two hard-coded path constants that point at it: `CatalogueLoader.OPENAPI_SPEC_PATH`
   and `OpenApiConformance.SPEC_PATH`. Diff the two contract versions first — the v0.3.0 → v0.4.0
   upgrade changed only the `resultCode` enum; if a future version changes `NowsDataItems` itself,
   the hand-written records in `api/model/nows/` need updating to match, by hand — they are not
   generated.
5. Run `:gob-simulator:test` (below) — a broken catalogue fails fast at Spring context startup in
   every integration test, so you will not get a silent wrong answer.

## Running the tests and PMD

```bash
./gradlew :gob-simulator:test
```

**PMD is opt-in and does not run as part of `build`.** Run it explicitly with the bare task name:

```bash
./gradlew pmdMain
```

(equally, `./gradlew :gob-simulator:pmdMain` — `gradle/pmd.gradle` matches either form). It is
disabled entirely for test sources (`pmdTest`).

### `failFast` truncates multi-failure diagnosis

`gradle/test.gradle` sets `failFast = true` for every project it's applied to, including this one.
**A failing test run stops at the first failure and does not run the rest.** If you are
diagnosing what looks like one failure, be aware you may be looking at only the first of several —
this has caused real confusion during development (see `task-8-report.md`). To see everything
that's actually broken, either fix and re-run iteratively, or temporarily flip `failFast` off
locally while diagnosing (do not commit that change without team agreement — it is a deliberate
CI setting).

### JDK toolchain

The build requires a **JDK 25 toolchain** (`gradle/java.gradle`). If it isn't auto-detected on
your machine, point Gradle at it explicitly:

```bash
./gradlew :gob-simulator:test :gob-simulator:pmdMain \
  -Porg.gradle.java.installations.paths=/path/to/jdk-25 \
  -Porg.gradle.java.installations.auto-detect=false
```

Cold builds against this module are slow (15–30 minutes has been observed) — don't assume a hang.

## Known gaps and open questions

These are current, known limitations — not things to silently work around:

- **15 result codes have no field mappings.** `ACNOTE`, `ACON`, `ADJNN`, `BPOC`, `BPOCRFSD`, `CSC`,
  `"ENF TEXT"`, `FIDIP`, `MP`, `ORD`, `REMCC`, `REMF`, `RT`, `TEXT`, `WDRN` are in the v0.4.0
  `resultCode` enum but the CIMD-4372 source table defines no required fields for them — they are
  explicit `fields: []` rows in `result-codes.yaml`, not an oversight. Posting them returns only
  default-filled entities. **Mappings are pending from the vendor.** (Two further codes, `NOENF`
  and `WDN`, are also `fields: []`, but for a different reason: they are in the enum with no
  CIMD-4372 table row at all, rather than being new-in-v0.4.0 codes awaiting mapping.)
- **Six field labels have no property anywhere in the bundled contract**: `Date of Offence`,
  `Start time of offence`, `End time of offence`, `Reserve Terms`, `Reason for decision`, and
  `[Directions]`. Each is marked `unmapped: true` with a `reason:` in `field-paths.yaml`. Several
  are required by result codes CP *can* post (e.g. `Date of Offence` by `BWTU`/`NBWT`/`S136`).
  Those labels are simply never emitted for those codes.
- **`Imposition.creditor` is omitted** from the response records — no CIMD-4372 field label maps
  into it. This is safe today because these records are only ever serialised outward (the
  simulator never parses an inbound Libra response into them), but it becomes a real gap the
  moment something needs creditor data out of this simulator.
- **401/403/404 are unreachable.** The simulator implements no security by design (this story's
  scope excludes it) — see the "What it is not" section above. (A framework 404 for an unknown
  URL — as opposed to an authentication/authorization 401/403 — is reachable: see
  `GlobalExceptionHandler`.)
- **`SeedStore`'s path-traversal guard is looser than its name suggests.** The only guard against
  `../../application`-style escapes via `GOB_SIMULATOR_SEED_DIR` is the regex
  `^[A-Za-z0-9-]{1,36}$` on the caseUrn — any 1-36 character run of ASCII letters, digits, and
  hyphens, not specifically "E followed by 9 digits" (a real caseUrn's actual shape). It is
  effective against traversal (no `.`, `/`, or `\` is ever accepted), but it is not a caseUrn
  format validator, and a future caller should not assume it rejects a malformed-but-traversal-safe
  caseUrn.
- **Amounts carry two decimal places by convention, not by contract.** The OpenAPI contract types
  amounts as JSON `number`, which has no scale. `BigDecimal` scale 2 is used throughout to
  preserve pounds-and-pence formatting, but nothing in the schema enforces that.
- **A latent trap in `NowsDataItemsAssembler.write()`**: the indexed-and-last-segment branch pads
  a list with non-null placeholder objects (`newBranch()`) up to the target index *before* the
  `putIfAbsent` guard runs (`overwrite || list.get(index) == null`). Because the placeholder is
  non-null, that guard would treat an index it just padded as "already occupied" and silently skip
  writing the real value — for a `baseline: true` path, that means the baseline default is
  silently dropped rather than the intended "don't overwrite what a posted code already supplied".
  **No current catalogue path has an indexed segment as its last segment** (the existing indexed
  paths, e.g. `offences.offence[0].impositions.imposition[0].amountImposed`, end in a plain field
  name), so this is dormant, not active. A code comment marks the exact line in `write()`. If a
  future catalogue entry needs a `baseline: true` path ending in `[n]`, this needs fixing first.

## Contract version

Bundled: `libra-gateway-hearing-events-v0.4.0.yml`. The upgrade from v0.3.0 changed only the
`resultCode` enum (24 → 42 values); `NowsDataItems` and its nested schemas are byte-identical to
v0.3.0. The core divergence this simulator works around — the CIMD-4372 ticket's nested response
shapes versus the contract's flatter one — was **not** resolved by that upgrade and remains open
with the vendor. See [pipeline artifact 001](../docs/pipeline/artifacts/001-libra-v030-contract-gaps.html).
