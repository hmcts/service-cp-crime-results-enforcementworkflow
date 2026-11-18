# Tasks: Payment terms in the hearing result sent to GOB (CIMD-4251)

**Input**: Design documents from `specs/004-cimd-4251-payment-terms/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: mandatory (constitution VII): tests are written first and must fail before the implementation task that
follows them.

**Scenario numbers** (2026-11-17): 43-48 were already taken, so this feature's scenarios are 49-56.

**Paths**: unprefixed paths are in this repo (`service-cp-crime-results-enforcementworkflow`). `A:` =
`../api-cp-crime-results-enforcementgateway`, `G:` = `../service-cp-crime-results-enforcementgateway`. Main code is
under `src/main/java/uk/gov/hmcts/cp/`, tests under `src/test/java/uk/gov/hmcts/cp/` (shortened below to `main/` and
`test/`).

**Rules for every task**: synthetic data only (no real amounts, dates, names or case references); never log or store
amounts, dates or prompt values (FR-014); no git commit/push or branch change (the user does these); JDK as the repo
requires; `./gradlew build` (PMD included) must pass at each checkpoint.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1-US4 from spec.md

---

## Phase 1: Setup (contract)

**Purpose**: the gateway contract allows a request without `paymentTerms`.

- [X] T001 Make `paymentTerms` optional in `HearingResultedRequest.required`, with a `LOCAL AMENDMENT (CIMD-4251)` comment and entry (3) in the amendment header, in `A:src/main/resources/openapi/openapi-spec.yml`; `./gradlew build` in `A:` passes (done 2026-11-15)
- [X] T002 User merges T001 to `team/CCT-1222` in `A:`; record the published draft version `0.0.0-<merge sha>` (Azure Artifacts `hmcts-lib`, verify the jar's `openapi/openapi-spec.yml` contains `LOCAL AMENDMENT (CIMD-4251`) in `specs/004-cimd-4251-payment-terms/research.md` R8. **Deferred by the user (2026-11-16) to the end of the feature**: until then this repo and `G:` build against a local copy published with `./gradlew publishToMavenLocal -DAPI_SPEC_VERSION=0.0.0-cimd4251-local` in `A:`; T002 replaces that version everywhere **Done 2026-11-18**: merged to `team/CCT-1222-V2` (PR #50, merge `0d0af98`; the hearing-confirmed work stays on `team/CCT-1222` for production first); published `0.0.0-0d0af98` verified (jar spec = branch spec; `instalmentFrequency`, `english_instalmentPaymentPeriod`, `paymentTerms` optional); set in this repo's and `G:` `build.gradle`; local copy removed.

- [X] T003 Export the implementation-plan HTML artifact (skill `export-design-artifact`, CLAUDE.md hard rule) to `docs/pipeline/artifacts/<NNN>-cimd-4251-payment-terms-plan.html` and surface it to the user before any Phase 2 code change (done 2026-11-16: `docs/pipeline/artifacts/008-cimd-4251-payment-terms-plan.html`)

**Checkpoint**: new contract version published and verified; implementation-plan artifact exported.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: version bump and the building blocks every story uses. No story label.

- [X] T004 Bump `uk.gov.hmcts.cp:api-cp-crime-results-enforcementgateway` (`implementation` and `apiSpec`) to the T002 version, updating the comment's merge sha/PR, in `build.gradle`; run `./gradlew build` (all existing tests still pass: the change only relaxes the contract)
- [X] T005 [P] Test first: `ResolvedResult` carries the result's prompts (empty list when the event has none; offence-level and defendant-level results both) in `test/service/ResultCodeResolverTest.java`; update the other `new ResolvedResult(...)` call sites in `test/service/HearingResultedProcessorTest.java` and `test/service/OffenceResultFilterTest.java`
- [X] T006 Add `List<HearingResultedEvent.JudicialResultPrompt> prompts` (never null) to `main/service/ResolvedResult.java` and fill it from `result.judicialResultPrompts()` in `main/service/ResultCodeResolver.java` (depends on T005)
- [X] T007 [P] Test first: binding and validation of `cp.hearing-result.payment-terms` (all keys of data-model.md §2; defaults: empty lists/maps, `date-formats` = `[yyyy-MM-dd]`; startup fails for a `frequency-values` target other than W/F/M, a prompt reference under two keys, a value in both `yes-values` and `no-values`) in `test/config/PaymentTermsPropertiesTest.java` (pattern: `ApplicationContextRunner` as in `test/config/HearingResultPropertiesTest.java`)
- [X] T008 Create `@Validated @ConfigurationProperties("cp.hearing-result.payment-terms")` class `main/config/PaymentTermsProperties.java` (nested `Prompts` with the seven lists; `frequencyValues`, `yesValues`, `noValues`, `dateFormats`; `@AssertTrue` checks for T007), register it like the other properties classes, and add the block with empty OQ-1 defaults and the comment from `contracts/payment-terms-configuration.md` to `src/main/resources/application.yaml` (depends on T007)
- [X] T009 [P] Test first: `PaymentValueParser` in `test/mapper/PaymentValueParserTest.java`: amounts `"500"`, `"500.00"`, `"£60"`, `"£1,500.00"` → 500, 500.00, 60, 1500.00; `"abc"`, `"-5"`, `"1.234"`, blank → unreadable; dates with the configured formats (ISO default; an unknown format → unreadable); frequency via `frequency-values` ignoring case/spaces, unknown → unreadable; yes/no via `yes-values`/`no-values` ignoring case, other → unreadable; results never contain the input value in their failure text
- [X] T010 Create `main/mapper/PaymentValueParser.java` (research R6; returns a value or a failure naming the logical field only) (depends on T008, T009)
- [X] T011 [P] Create `main/mapper/PaymentTermsOutcome.java` (sealed: `None`, `Block(PaymentTerms block)`, `Unsendable(MappingFailureReason reason, String detail)`) and add `PAYMENT_TERMS_INCOMPLETE`, `PAYMENT_TERMS_UNREADABLE`, `PAYMENT_TERMS_CONFLICTING` to `main/mapper/MappingFailureReason.java` (keep `PAYMENT_DUE_DATE_UNAVAILABLE` until T017)
- [X] T012 [P] Create test profile `src/test/resources/application-paymentterms-test.yaml` with clearly fake references (`test-instalment-amount`, `test-frequency`, `test-lump-sum`, `test-instalment-start-date`, `test-pay-by-date`, `test-payment-card-required`, `test-parent-guardian-to-pay`), `frequency-values` (`test-weekly: W`, `test-fortnightly: F`, `test-monthly: M`), `yes-values: [test-yes]`, `no-values: [test-no]`; add `paymentterms-test` to `@ActiveProfiles` in `test/integration/EmbeddedBrokerIntegrationTestBase.java`

**Checkpoint**: `./gradlew build` passes; prompts reach `ResolvedResult`; configuration and parser exist; behaviour unchanged.

---

## Phase 3: User Story 2 - No payment terms result, no payment terms block (Priority: P1) MVP

**Goal**: requests without a payment terms result carry no `paymentTerms`; the stand-in block and the fallback setting are gone (FR-002, R1).

**Independent Test**: scenario with MPSO only → request has no `paymentTerms` and is sent; `GatewayContract` accepts it.

### Tests for User Story 2

- [X] T013 [P] [US2] Test first in `test/mapper/HearingResultedRequestMapperTest.java`: with no INSTL/LUMSI/PDATE/PTFOR result the mapped request has `getPaymentTerms() == null` and passes `GatewayContract` validation; remove the `PaymentDueDateFallback` parameter from `mapped(...)`/`mapper(...)` and the two fallback-specific tests (lines ~116-135), keeping every other assertion
- [X] T014 [P] [US2] Remove `"paymentTerms"` from `src/test/resources/expected/hearing-resulted-request-minimum.json` and from `expected-request.json` in scenarios `01`, `15`, `16`, `17`, `18`, `22` under `src/test/resources/scenarios/` (change nothing else in those files)
- [X] T015 [P] [US2] Add scenario `src/test/resources/scenarios/49-payment-terms-mpso-without-payment-terms-sent-without-block/` (`scenario.json` + `expected-request.json`, new synthetic event `src/test/resources/events/hearing-resulted-payment-terms-mpso-only.json` with one MPSO result and no prompts): one POST, no `paymentTerms`, `SUCCEEDED` (CIMD-4251 AC2) (run 2026-11-17: passed)

### Implementation for User Story 2

- [X] T016 [US2] In `main/mapper/HearingResultedRequestMapper.java` remove the stand-in `paymentTerms` (lines ~97-100) and the fallback branch; update the class Javadoc (no `payment-due-date-fallback`); request built without `paymentTerms` (T013-T015 pass). **Keep a safe guard** (constitution IX, analysis C1): when the submission has an INSTL/LUMSI/PDATE/PTFOR result, return `failed(PAYMENT_TERMS_UNREADABLE, "<code> payment terms not mapped yet")` (test first in `test/mapper/HearingResultedRequestMapperTest.java`), so a payment terms result is never sent without its block; T024 replaces this guard with `PaymentTermsMapper`
- [X] T017 [US2] Remove the fallback setting: delete `main/config/PaymentDueDateFallback.java`; remove `paymentDueDateFallback` from `main/config/HearingResultProperties.java`, `payment-due-date-fallback` from `src/main/resources/application.yaml`, `PAYMENT_DUE_DATE_FALLBACK` from `docker-compose.yml`; remove `PAYMENT_DUE_DATE_UNAVAILABLE` from `main/mapper/MappingFailureReason.java`; remove the fallback assertions from `test/config/HearingResultPropertiesTest.java` and the `@TestPropertySource` fallback property from `test/integration/WorkflowStubsIntegrationTestBase.java`
- [X] T018 [US2] Replace the fallback warning in `main/config/HearingResultStartupChecks.java` with the R9 warning (one line naming the empty `cp.hearing-result.payment-terms` keys, no values), test first in `test/config/HearingResultStartupChecksTest.java` (create if absent: warns when empty, silent when all configured)
- [X] T019 [US2] Replace `test/integration/PaymentDueDateNoneIntegrationTest.java` with `test/integration/PaymentTermsUnconfiguredIntegrationTest.java` (extends `WorkflowStubsIntegrationTestBase`, application defaults i.e. no `paymentterms-test` profile): a result with no payment terms result is `SUCCEEDED` with one POST and no `paymentTerms` in the stored request payload; `hearing-resulted-enforcement-payment-terms.json` with `INSTL` stubbed is `MAPPING_FAILED`, `error_detail` starts `PAYMENT_TERMS_UNREADABLE` (T016 guard), with no POST (run 2026-11-17: passed) - *2026-11-18 (OQ-1 resolved): renamed `PaymentTermsApplicationConfigIntegrationTest`, now runs the real `application.yaml` values (see "OQ-1 resolved" below)*

**Checkpoint**: US2 complete and shippable on its own (T016-T017 remove the stand-in date risk); `./gradlew build` passes.

---

## Phase 4: User Story 1 - Instalment arrangements reach GOB (Priority: P1)

**Goal**: INSTL and LUMSI produce the block per the mapping table; missing/unreadable/conflicting values make the share unsendable (FR-003, FR-004, FR-006, FR-009 to FR-011).

**Independent Test**: SC + INSTL (500, weekly, start date) → `paymentTerms` = `W`, start date, 500, `N`, `N`, no `lumpSum`.

### Tests for User Story 1

- [X] T020 [P] [US1] Test first in `test/mapper/PaymentTermsMapperTest.java` (properties from the T012 fake references): no payment terms result → `None`; AC1 SC + INSTL weekly 500 → `W`/500/start date/`N`/`N`, no lumpSum; 3.1 INSTL monthly → `M`; 3.2 LUMSI → adds lumpSum 2500; AC3 the same INSTL block under each parent SC, MPSO, PGPAY, FTTP; INSTL without amount / frequency / start date → `Unsendable(PAYMENT_TERMS_INCOMPLETE)`; LUMSI without lump sum → incomplete; unreadable amount/date/frequency → `PAYMENT_TERMS_UNREADABLE`; no references configured → unreadable with "not configured" detail; two equal INSTL blocks → one `Block`; INSTL + LUMSI with different values → `PAYMENT_TERMS_CONFLICTING`; detail text never contains an amount or date; enforcement override fields always null; an extra optional prompt with no payload field (DID, fake reference `test-did`) is ignored (analysis L2)
- [X] T021 [P] [US1] Add synthetic events (use distinctive synthetic values that cannot match other log text, e.g. amounts `487.13`, `2500.37`, dates in 2031, and add them to `NEVER_LOGGED` in `test/integration/ScenarioIntegrationTest.java`; analysis L1) `src/test/resources/events/hearing-resulted-payment-terms-sc-instl.json` (SC + INSTL with the T012 fake prompt references: amount 500, `test-weekly`, start date) and `hearing-resulted-payment-terms-fttp-lumsi.json` (FTTP + LUMSI: lump sum 200, amount 30, `test-fortnightly`, start date); `hearing-resulted-payment-terms-instl-missing-amount.json`; `hearing-resulted-payment-terms-conflicting.json` (INSTL and LUMSI with different values) (run 2026-11-17: passed)
- [X] T022 [P] [US1] Add scenarios under `src/test/resources/scenarios/`: `50-payment-terms-sc-instl-sent-with-block/` (expected-request with the INSTL block, `SUCCEEDED`), `51-payment-terms-fttp-lumsi-sent-with-lump-sum/`, `52-payment-terms-incomplete-mapping-failed/` (`MAPPING_FAILED`, no POST, `error_detail` starts `PAYMENT_TERMS_INCOMPLETE`), `53-payment-terms-conflicting-mapping-failed/` (`PAYMENT_TERMS_CONFLICTING`); each asserts no amount or date from the fixture appears in the logs (extend `ScenarioIntegrationTest`'s `Expected` only if a log assertion field is missing) (run 2026-11-17: passed)

### Implementation for User Story 1

- [X] T023 [US1] Create `main/mapper/PaymentTermsMapper.java` (`@Component`): select the submission's results with `cpShortCode` in {INSTL, LUMSI, PDATE, PTFOR}; read prompts by configured reference; build the INSTL/LUMSI block with `PaymentValueParser` (flags `N` for now); apply R5 (incomplete / unreadable / not configured) and R7 (equal blocks collapse, different → conflicting); never set enforcement override fields (depends on T010, T011)
- [X] T024 [US1] Wire `PaymentTermsMapper` into `main/mapper/HearingResultedRequestMapper.java`, replacing the T016 guard (and its test): `Block` → `request.paymentTerms(block)`, `None` → omitted, `Unsendable` → `failed(reason, detail)` after the existing checks; inject via constructor
- [X] T025 [US1] Complete `test/integration/PaymentTermsUnconfiguredIntegrationTest.java` (T019): the INSTL share's `error_detail` starts `PAYMENT_TERMS_UNREADABLE` and mentions "not configured" (run 2026-11-17: passed) - *2026-11-18 (OQ-1 resolved): renamed `PaymentTermsApplicationConfigIntegrationTest`, now runs the real `application.yaml` values (see "OQ-1 resolved" below)*

- [X] T026 [US1] Add scenario `src/test/resources/scenarios/56-payment-terms-already-sent-not-repeated/` (two shares, as in scenario 38: the INSTL result is sent with its block in the first share; the later share adds a new offence only, so the INSTL result is left out by `OffenceResultFilter` and the second request has no `paymentTerms`) (spec edge case; analysis M1) (run 2026-11-17: passed)

**Checkpoint**: T020-T025 and T026 pass; INSTL/LUMSI end to end through the scenario suite.

---

## Phase 5: User Story 3 - Pay-by-date arrangements reach GOB (Priority: P2)

**Goal**: PDATE and PTFOR send only the due date and the flags (FR-005).

**Independent Test**: PDATE with pay-by date → `paymentDueDate` only plus flags; no frequency, amount or lump sum.

- [X] T027 [P] [US3] Test first in `test/mapper/PaymentTermsMapperTest.java`: 3.3 PDATE → due date = pay-by date, no `instalmentFrequency`/`instalmentAmount`/`lumpSum`; 3.4 PTFOR same shape; PDATE without pay-by date → incomplete; unreadable pay-by date → unreadable
- [X] T028 [P] [US3] Add synthetic event `src/test/resources/events/hearing-resulted-payment-terms-sc-pdate.json` and scenario `src/test/resources/scenarios/54-payment-terms-pdate-sent-with-due-date-only/` (run 2026-11-17: passed)
- [X] T029 [US3] Add the PDATE/PTFOR rule to `main/mapper/PaymentTermsMapper.java` (depends on T023)

**Checkpoint**: US3 passes with US1 and US2 still green.

---

## Phase 6: User Story 4 - Payment card and parent-to-pay flags (Priority: P3)

**Goal**: `paymentCardRequested` / `parentToPay` are `Y` only when the prompt says yes, `N` otherwise (FR-007, FR-008).

**Independent Test**: PGPAY + INSTL with both prompts `test-yes` → `Y`, `Y`.

- [X] T030 [P] [US4] Test first in `test/mapper/PaymentTermsMapperTest.java`: 3.5 prompts absent (INSTL, PDATE, PTFOR) → `N`, `N`; prompts `test-no` → `N`, `N`; 3.6 PGPAY + INSTL with both `test-yes` → `Y`, `Y`; a flag prompt with another value → `PAYMENT_TERMS_UNREADABLE`
- [X] T031 [P] [US4] Add synthetic event `src/test/resources/events/hearing-resulted-payment-terms-pgpay-flags-yes.json` and scenario `src/test/resources/scenarios/55-payment-terms-pgpay-card-and-parent-yes/` (run 2026-11-17: passed)
- [X] T032 [US4] Read the two flag prompts in `main/mapper/PaymentTermsMapper.java` (depends on T023)

**Checkpoint**: all four stories pass; `./gradlew build` green.

---

## Phase 7: Gateway (contract consumer)

**Purpose**: the gateway forwards a request without `paymentTerms` (CLAUDE.md: integration test for the changed endpoint behaviour).

- [X] T033 Bump the contract version (`implementation` and `apiSpec`) to the T002 version, with its comment, in `G:build.gradle`
- [X] T034 Test in `G:src/test/java/uk/gov/hmcts/cp/integration/HearingResultedGatewayIntegrationTest.java`: `POST /hearingResulted` with the fixture minus `paymentTerms` (build it from `G:src/test/resources/hearingresulted/request.json` in the test) → 200, the request WireMock receives has no `paymentTerms`, nothing else changed; `./gradlew build` in `G:` passes (depends on T033)

---

## Phase 8: Polish & cross-cutting

- [X] T035 [P] Update `README.md`: remove `PAYMENT_DUE_DATE_FALLBACK` (table row ~93, run command ~118, warning ~145); replace `PAYMENT_DUE_DATE_UNAVAILABLE` with the three `PAYMENT_TERMS_*` reasons (~133); document `cp.hearing-result.payment-terms` and the OQ-1 interim behaviour
- [X] T036 [P] Mark feature 001 T066 (`paymentDueDate` ruling only) and T069 as superseded by `specs/004-cimd-4251-payment-terms` in `specs/001-cimd-4246-hearing-resulted-to-libra/tasks.md` (strike-through note, do not tick); remove `PAYMENT_DUE_DATE_FALLBACK=HEARING_DATE` from the run command in `specs/003-enforcement-nows-trigger/quickstart.md`
- [ ] T037 [P] Before a release build: replace the draft contract version with the fixed `X.Y.Z` once published, in `build.gradle` and `G:build.gradle` (`validateApiSpecVersions`); tracked, not blocking STE
- [X] T038 Run `./gradlew clean build` in this repo and in `G:`; all tests and PMD pass; record the counts in `specs/004-cimd-4251-payment-terms/quickstart.md`
- [ ] T039 Release blocker (FR-014, constitution IV; analysis M3): the workflow's TEMP DIAGNOSTICS logging (prints request/reply payloads, now including payment amounts and dates) is rolled back before `main`; confirm with the user before release
- [X] T040 Grep the changed main code and test logs for amounts/dates/prompt values in log statements and `error_detail` texts (FR-014, constitution IV); fix any finding

---

## Implementation notes (2026-11-17)

- T016 guard was implemented and then replaced by T024, as planned (no release in between).
- 2026-11-18, Libra v0.8.0 (`src/test/resources/contracts/libra-gateway-hearing-events-openapi-v0.8.0.yml`, an unchanged copy) is the reference:
  - the frequency inside the block is `instalmentFrequency` (Libra's name since v0.6.0); the first build sent
    `paymentTerms.paymentTerms` (the v0.4.0 name), which Libra and the simulator would reject
    (`additionalProperties: false`). Fixed in the contract (`A:`), the workflow mapper, tests and expected requests;
  - the workflow's test copy of Libra's contract is now v0.8.0 unchanged (`src/test/resources/contracts/`), replacing
    the amended v0.4.0 copy, so `LibraContract` checks requests against Libra's actual contract;
  - contract `NowsDataRequest.nowsDataItems` no longer `required`, as in v0.8.0;
  - `Terms.english_instalmetPaymentPeriod` renamed `english_instalmentPaymentPeriod` (v0.8.0) across the contract,
    gateway fixture, hearing-nows (`cpp-context-hearing-nows`, branch `team/CCT-1222`) and the workflow/simulator
    (OQ-4). The simulator now bundles Libra v0.8.0 instead of v0.6.0.
  - hearing-nows local build (JDK 17): domain-common, persistence-api, event-sources green; event-processor and
    command-api need the internal Artifactory (VPN) and were not built locally - to be built in CI / on VPN.
- Unconfigured payment card / parent-guardian prompt references make the share `PAYMENT_TERMS_UNREADABLE`
  ("not configured") rather than sending `N` unchecked (constitution IX). A configured but absent prompt gives `N`.
- A payment terms short code without its result detail is `PAYMENT_TERMS_UNREADABLE` ("result detail missing"): never
  sent without its block.
- T033-T034 gateway: `./gradlew build` green (132 tests, PMD clean).
- T038 workflow `./gradlew clean build` green 2026-11-17: 506 tests (54 scenarios, incl. 49-56) + simulator 260, 0 failures, PMD clean.

## Review 2026-11-18 (own review + independent reviewer), all fixed and tested

- `frequency-values` is now `W`/`F`/`M` → list of captured values: as map keys, Spring dropped spaces and dots from
  captured values ("every week" bound as "everyweek"). Lower-case keys (environment variables) accepted.
- Amounts: a comma is accepted only as a proper thousands separator; `1,50` (decimal comma) was read as 150.
- Dates: built with `parseDefaulting(ERA)` instead of rewriting `y`→`u` in the pattern (corrupted quoted text);
  English, case-insensitive month names (`Sep`, `SEP`; en_GB data spells it "Sept").
- A prompt captured twice with different values is unreadable (was: first one used silently); same meaning
  (`500`/`£500.00`) is accepted.
- "not configured" (not "not readable") when references are set but no frequency or yes/no values exist.
- `ResolvedResult.toString` no longer prints prompt values (FR-014 safety).
- Mapper restructured: every value is read and checked before the block is built.
- Tests: scenario 57 (MPSO + PTFOR, 3.4); exact `error_detail` in 52/53; gateway IT with a full LUMSI block;
  `2,500.37` added to `NEVER_LOGGED`; synthetic amount 31.29 replaced (it matched log timestamps `..:31.29x`, flaky).
- Docs: FR-014 scoped to logs and `error_detail`; note on results without reference data; deploy order in quickstart.
- Builds: workflow 528 + simulator 261 tests, gateway 133, contract green; PMD clean.

## OQ-1 resolved 2026-11-18 (user: resolve before STE testing)

- `src/main/resources/application.yaml` `cp.hearing-result.payment-terms` set from CP itself (research R3 table):
  references `IAMT`, `PF`, `LSA`, `instalmentStartDate`/`ISTD`, `PBD`, `PAYMENT_CARD_REQUIRED`,
  `PARENT_GAURDIAN_TO_PAY`; frequency `weekly`/`fortnightly`/`monthly`; yes `Yes`/`true`, no `No`/`false`; dates
  `dd/MM/yyyy`, `yyyy-MM-dd`, `dd MMM yyyy`. No code change; the startup warning no longer fires.
- `PaymentTermsUnconfiguredIntegrationTest` → `PaymentTermsApplicationConfigIntegrationTest` (application defaults):
  no payment terms result → sent without block; LUMSI with CP-shaped prompts (`£2500.37`, `£73.61`, `monthly`,
  `11/04/2031`, `true`, `No`) → block M / 73.61 / 2500.37 / 2031-04-11 / Y / N; PDATE (`16/05/2031`, `Yes`) →
  2031-05-16 / N / Y; INSTL without prompts → `PAYMENT_TERMS_INCOMPLETE: INSTL instalment-amount missing`. New
  fixtures `events/hearing-resulted-enforcement-payment-terms-cp-{lumsi,pdate}.json` (own definition ids: the
  reference data client caches short codes per Spring context).
- Final review: CP puts payment terms results of a case share in `hearing.defendantJudicialResults` (real samples
  in `cpp-context-results` tests: INSTL there with `£455.00`, `fortnightly`, `11/11/2025`); the resolver already read
  that level, now tested too (fixture `…-cp-instl-hearing-level.json`, block F / 487.13 / 2031-03-07 / N / N).
- Build: `./gradlew clean build pmdMain pmdTest` (+ simulator PMD) green, 531 workflow + 261 simulator tests,
  0 failures, PMD clean. Note: `build` alone does not run PMD in this repo.

## Dependencies & Execution Order

```text
T001 ─► T002 ─► T003 ─► Phase 2 (T004..T012) ─► US2 (T013..T019) ─► US1 (T020..T026) ─► US3 (T027..T029)
                                                                                   └──► US4 (T030..T032)
T002 ─► Gateway (T033..T034)        [independent of the workflow phases]
All ─► Polish (T035..T040)
```

- **Phase 2** blocks all stories. Inside it: T005→T006, T007→T008, T008+T009→T010; T011 and T012 are free.
- **US2** first (MVP): it changes the shared mapper and the existing scenarios that later stories rely on.
- **US1** needs US2 (mapper without the stand-in) and Phase 2. **US3** and **US4** need T023 (the mapper class) and can
  then run in parallel with each other (same file: do them one after the other if one person edits
  `PaymentTermsMapper.java`).
- **Gateway** needs only T002.

## Parallel examples

- Phase 2: T005, T007, T009, T011, T012 together (different files).
- US2 tests: T013, T014, T015 together.
- US1: T020, T021, T022 together, then T023 → T024 → T025.
- Gateway T033-T034 alongside any workflow phase after T002.

## Implementation Strategy

1. **MVP = US2**: after T001-T019 the stand-in due date is gone, every hearing without a payment terms result is
   sent without a block, and a hearing with one stays unsent (`PAYMENT_TERMS_UNREADABLE`, T016 guard) - never sent
   without its block. Deployable to STE on its own.
2. **US1**: instalment arrangements, the main CIMD-4251 value; plus all the unsendable handling.
3. **US3, US4**: increments on the same mapper.
4. **Gateway** and **Polish**.
5. **OQ-1**: resolved 2026-11-18 (see "OQ-1 resolved" above); STE testing confirms the values against real shares.
6. **OQ-2, OQ-3**: concluded with the BA after implementation (user, 2026-11-15); any change follows then.
