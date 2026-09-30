---
description: "Task list for CIMD-4246 — Hearing Resulted to GOB (Libra) for Enforcement Cases"
---

# Tasks: Hearing Resulted to GOB (Libra) for Enforcement Cases

**Input**: Design documents from `specs/001-cimd-4246-hearing-resulted-to-libra/`
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/README.md), [quickstart.md](quickstart.md)

**Tests**: INCLUDED. Constitution **Principle VII** (tests first; PMD clean; unit + integration tests for each new component) makes them part of done. In each story, write the tests first and confirm they fail before implementing.

**Organization**: tasks are grouped by user story (spec.md US1-US6), so each story can be implemented and tested on its own.
- Plan iteration 0 → Phases 1-2
- Iteration 1 → US1-US4
- Iteration 2 → US5
- Iteration 3 → US6
- Iteration 4+ → Polish / follow-ups

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: the user story it belongs to (US1…US6)

## Path Conventions

This feature spans **three repos**, all siblings under `/Users/macpro/HMCTS/github/`:

| Prefix used below | Repo |
|---|---|
| *(none)* | `service-cp-crime-results-enforcementworkflow` (this repo, called **W**) |
| `../service-cp-crime-results-enforcementgateway/` | enforcement gateway service (**G**) |
| `../api-cp-crime-results-enforcementgateway/` | gateway API contract (**A**) |

Java packages: main `src/main/java/uk/gov/hmcts/cp/…`, tests `src/test/java/uk/gov/hmcts/cp/…`, in every repo.

Conventions to copy:
- Listener, client and service style: `../service-cp-crime-results-enforcementgateway/src/main/java/uk/gov/hmcts/cp/{messaging,client,service,config}/`
- Persistence and test DB: `../service-cp-crime-results-pcr/` (`build.gradle:57-62`, `src/main/resources/application.yaml`, `src/main/resources/db/migration/`, `src/test/java/uk/gov/hmcts/cp/integration/config/PostgresInitialise.java`, `src/main/java/uk/gov/hmcts/cp/clients/ReferenceDataClient.java`)

Branches: W is on `001-cimd-4246-hearing-resulted-to-libra`. A and G: branch `dev/CIMD-4246` in each repo (committed by the developer as `180a860` and `dbc6339`; later review fixes are uncommitted).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: publish the contract and add the dependencies both services need.

- [X] T001 In A, add a global `tags:` entry (`name: enforcement-hearing`, a description) to `../api-cp-crime-results-enforcementgateway/src/main/resources/openapi/openapi-spec.yml`. This clears the Spectral `operation-tag-defined` warning. Then run `./gradlew clean build` and `npx @stoplight/spectral-cli lint src/main/resources/openapi/openapi-spec.yml` in A; expect 9 tests passing and 0 problems.
- [ ] T002 *(In progress: committed in A on `dev/CIMD-4246` (`180a860`), published to Maven local as `0.0.999`, which W and G use for now. **Pending:** push + PR, then swap `0.0.999` for the published draft version in W/G `build.gradle`. Since 2026-09-28 `validateApiSpecVersions` rejects `0.0.999`, so a release can't go out with the placeholder.)* In A, commit `openapi-spec.yml` and `src/test/java/uk/gov/hmcts/cp/config/OpenApiObjectsTest.java` on branch `team/CCT-1222` and open a PR. After merge, note the published draft version (`vX.Y.Z-<sha>`, see `../api-cp-crime-results-enforcementgateway/docs/OPENAPI-SPEC-VERSIONING.md`) in plan.md §2 "New dependencies". For local work before publication, run `./gradlew publishToMavenLocal` in A.
- [X] T003 [P] In W, update `build.gradle`:
  - add `implementation('uk.gov.hmcts.cp:api-cp-crime-results-enforcementgateway:<version from T002>')`;
  - add the PCR persistence set: `spring-boot-starter-data-jpa`, `org.postgresql:postgresql`, `spring-boot-starter-flyway`, `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`;
  - add `spring-boot-starter-validation`;
  - add test deps `org.wiremock:wiremock-standalone:3.13.2` and `com.networknt:json-schema-validator` (latest 1.x);
  - add an `apiSpec` configuration holding the same API coordinate, plus `apply from: "$rootDir/gradle/apispec-validation.gradle"`;
  - in `.github/workflows/ci-released.yml`, **re-add the `validate-api-spec-version` job** (checkout, setup-java 25, setup-gradle, `./gradlew validateApiSpecVersions`), copied from `../service-cp-crime-results-pcr/.github/workflows/ci-released.yml:15-26`. Make `ci-release` `needs:` it, and delete the "removed until … wires its apiSpec" NOTE (constitution Principle II).
- [X] T004 [P] In G, add `implementation('uk.gov.hmcts.cp:api-cp-crime-results-enforcementgateway:<version from T002>')` and `spring-boot-starter-validation` to `../service-cp-crime-results-enforcementgateway/build.gradle`. Copy `gradle/apispec-validation.gradle` from W and wire it up as in T003, **including re-adding the `validate-api-spec-version` job to `../service-cp-crime-results-enforcementgateway/.github/workflows/ci-released.yml` and deleting its NOTE** (constitution Principle II). Confirm `./gradlew compileJava` resolves `uk.gov.hmcts.cp.openapi.api.EnforcementHearingApi`.
- [X] T005 [P] In W's `docker-compose.yml`:
  - add a `postgres` service: `postgres:18-alpine`, `POSTGRES_DB: enforcementworkflowdb`, `POSTGRES_USER/PASSWORD: postgres`, port `5432:5432`, and a `pg_isready` healthcheck;
  - give `app` the env `DATASOURCE_URL: jdbc:postgresql://postgres:5432/enforcementworkflowdb` and `PAYMENT_DUE_DATE_FALLBACK: HEARING_DATE` (local only, research.md R13), plus `depends_on: postgres: condition: service_healthy`.
- [X] T006 [P] In W's `src/main/resources/application.yaml`, add the `spring.datasource`, `spring.flyway` and `spring.jpa` (`open-in-view: false`, `hibernate.ddl-auto: validate`) keys exactly as in data-model.md §4.
- [X] T007 [P] In G, update `../service-cp-crime-results-enforcementgateway/docs/LibraApimMockPolicy.md` with the APIM request for the platform team, per `contracts/apim-libra-hearingresulted.md`:
  - operation `libra-hearingresulted` under `cppi-v4`, `POST /hearingResulted` → Libra `POST /hearing/result`;
  - response status and body passed through;
  - the mock returns the corrected sample `HearingResultedResponse` body;
  - the policy sets `forward-request timeout="35"` (research.md R20).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the persistence schema, the event model and the HTTP infrastructure that every story uses.

**⚠️ CRITICAL**: no user-story work can start until this phase is complete.

- [X] T008 Create `src/main/resources/db/migration/V1.001__create_hearing_result_submission.sql` with the table, the unique index `uq_hrs_hearing_case_defendant`, the index `idx_hrs_case_urn` and the index `idx_hrs_status_updated_at` (the stale-SENDING sweep, research.md R19), exactly as in data-model.md §3.
- [X] T009 [P] Create `src/main/java/uk/gov/hmcts/cp/entity/SubmissionStatus.java`: enum `SENDING, SUCCEEDED, FAILED, MAPPING_FAILED, SKIPPED_NO_RESULT_CODE`.
- [X] T010 Create `src/main/java/uk/gov/hmcts/cp/entity/HearingResultSubmissionEntity.java`: a JPA `@Entity` mapped to `hearing_result_submission`.
  - `requestPayload` / `responsePayload` are `String` with `@JdbcTypeCode(SqlTypes.JSON)`.
  - `status` is `@Enumerated(STRING)`.
  - `createdAt` / `updatedAt` are `Instant`, set in `@PrePersist` / `@PreUpdate`.
  - Use Lombok `@Getter @Setter @NoArgsConstructor`, as the PCR entities do. (Depends on T008, T009.)
- [X] T011 Create `src/main/java/uk/gov/hmcts/cp/repository/HearingResultSubmissionRepository.java` extending `JpaRepository<HearingResultSubmissionEntity, UUID>`, with `Optional<HearingResultSubmissionEntity> findByHearingIdAndCaseIdAndDefendantId(UUID, UUID, UUID)`. (Depends on T010.)
- [X] T012 [P] Create `src/test/java/uk/gov/hmcts/cp/integration/config/PostgresInitialise.java`, copied from `../service-cp-crime-results-pcr/src/test/java/uk/gov/hmcts/cp/integration/config/PostgresInitialise.java`. Use DB `enforcementworkflowdb`, pool size 4, and a failure message telling the developer to run `docker compose up -d postgres`.
- [X] T013 Create `src/test/java/uk/gov/hmcts/cp/integration/IntegrationTestBase.java` (`@SpringBootTest`, `@ContextConfiguration(initializers = PostgresInitialise.class)`, `@AutoConfigureMockMvc`) and make `src/test/java/uk/gov/hmcts/cp/integration/ActuatorIntegrationTest.java` extend it. (Depends on T012.)
- [X] T014 Create `src/test/java/uk/gov/hmcts/cp/repository/HearingResultSubmissionRepositoryIntegrationTest.java` (extends `IntegrationTestBase`). It asserts that Flyway V1.001 applied, the `jsonb` round-trip works, `findByHearingIdAndCaseIdAndDefendantId` works, and a duplicate `(hearing_id, case_id, defendant_id)` insert throws `DataIntegrityViolationException`. (Depends on T011, T013.)
- [X] T015 [P] Create `src/main/java/uk/gov/hmcts/cp/event/HearingResultedEvent.java`: nested Java records per data-model.md §1, each with `@JsonIgnoreProperties(ignoreUnknown = true)`, following `../service-cp-crime-results-enforcementgateway/src/main/java/uk/gov/hmcts/cp/event/ConfirmedHearingEvent.java`. Include `JudicialResult(UUID judicialResultId, UUID judicialResultTypeId, String label, LocalDate orderedDate, List<JudicialResultPrompt> judicialResultPrompts)`.
- [X] T016 [P] Create the event fixtures in `src/test/resources/events/`, starting from `../cpp-platform-core-domain/DesignSchemas/public/sample/hearing.events.hearing-resulted-firstHearing.json`:
  - `hearing-resulted-enforcement.json`: one case with `prosecutionAuthorityOUCode: "GAPGD00"`, `caseURN: "E012345678"`, one individual defendant with `prosecutionAuthorityReference: "1234567890"` and `personDetails.address.address1`, and `isReshare: false`;
  - `hearing-resulted-reshare.json` (`isReshare: true`);
  - `hearing-resulted-non-enforcement.json` (another OU code);
  - `hearing-resulted-two-defendants.json`;
  - `hearing-resulted-two-enforcement-cases.json`;
  - `hearing-resulted-linked-application.json` (`courtApplications[].courtApplicationCases[].prosecutionCaseId` = the case id);
  - `hearing-resulted-unknown-codes-only.json`;
  - `hearing-resulted-missing-account-number.json`;
  - `hearing-resulted-organisation-defendant.json`.
- [X] T017 Create `src/test/java/uk/gov/hmcts/cp/event/HearingResultedEventTest.java`: deserialise every fixture from T016 with the Spring `ObjectMapper`, and assert the key fields (defendant-level `prosecutionAuthorityReference`, `judicialResultTypeId`, `hearingDay`, `isReshare`). (Depends on T015, T016.)
- [X] T018 [P] Create `src/main/java/uk/gov/hmcts/cp/config/RestClientConfig.java` with **two** prototype `RestClient.Builder` beans, built the same way as the first bean in `../service-cp-crime-results-enforcementgateway/src/main/java/uk/gov/hmcts/cp/config/RestClientConfig.java`:
  - `referenceDataRestClientBuilder`: `cp.reference-data.connect-timeout-ms` (5000) / `read-timeout-ms` (10000);
  - `enforcementGatewayRestClientBuilder`: `cp.enforcement-gateway.connect-timeout-ms` (5000) / `read-timeout-ms` (50000).
  Add the keys to `src/main/resources/application.yaml` per data-model.md §4 (research.md R20). Add `src/test/java/uk/gov/hmcts/cp/config/RestClientConfigTest.java`, asserting the default values and that the gateway read timeout (50000) exceeds 45000, the gateway's own budget.
- [X] T019 [P] Create `src/main/java/uk/gov/hmcts/cp/config/HearingResultProperties.java`: `@ConfigurationProperties("cp.hearing-result")` + `@Validated`, enabled through `@ConfigurationPropertiesScan` on `src/main/java/uk/gov/hmcts/cp/Application.java`. Fields:
  - `Map<String,String> resultCodeRenames` (default `{TFOUT: TFOOUT, WC: DW, WWDN: WDN}`);
  - `PaymentDueDateFallback paymentDueDateFallback` (enum `HEARING_DATE, NONE`, default `NONE`);
  - `Map<String, List<String>> nowsDataItemsByShortCode` (default empty);
  - `Duration staleSendingThreshold` (default `PT5M`) and `Duration staleSendingSweepInterval` (default `PT1M`) (research.md R19).
  Add the `cp.hearing-result.*` and `cp.enforcement.authority-code` keys to `application.yaml` per data-model.md §4. Set `payment-due-date-fallback: ${PAYMENT_DUE_DATE_FALLBACK:NONE}`; local/test profiles override it to `HEARING_DATE`. Log a WARN at startup while the value is `HEARING_DATE` ("placeholder paymentDueDate enabled – not for production"), and add a test for that warning (research.md R13).
- [X] T020 [P] Create `specs/001-cimd-4246-hearing-resulted-to-libra/adrs/001-CIMD-4246-hearing-result-payload-pii-at-rest.md` (status Proposed).
  - Context: request and response `jsonb` hold defendant PII and `CtBankDetails`.
  - Decision: plain storage in iteration 1, with access limited to the service DB role.
  - Consequences: field-level encryption deferred to iteration 4+, following `../service-cp-crime-results-pcr/docs/pipeline/adrs/004-AMP-891-carry-defendant-pii-encrypted-at-rest.md`.

**Checkpoint**: `docker compose up -d postgres && ./gradlew build` is green in W (Flyway applied, fixtures parse). G compiles against the API jar.

---

## Phase 3: User Story 1 - Resulted enforcement hearing is sent to GOB and GOB's reply is kept (Priority: P1) 🎯 MVP

**Goal**: a first share of a single-defendant GAPGD00 case with GOB-recognised results produces one schema-valid `HearingResultedRequest`. It goes W → G → APIM, and the `HearingResultedResponse` is stored as `SUCCEEDED`.

**Independent Test**: `HearingResultedFlowIntegrationTest` (T041). It feeds `hearing-resulted-enforcement.json` with WireMock standing in for reference data and G. It asserts exactly one POST with an unchanged `caseUrn`/`prosecutorDefendantId` and renamed codes, and one `SUCCEEDED` row holding the response body. The non-enforcement fixture produces no POST.

### Tests for User Story 1 ⚠️ (write first, must fail)

- [X] T021 [P] [US1] Create `../service-cp-crime-results-enforcementgateway/src/test/java/uk/gov/hmcts/cp/client/LibraClientResultHearingTest.java` with `MockRestServiceServer`:
  - POST `/hearingResulted` carries the `Ocp-Apim-Subscription-Key` header and a JSON body;
  - a 200 body maps to `HearingResultedResponse`;
  - a 400/404/500 with `{errorCode, errorDescription}` throws `LibraCallException` with those fields;
  - a transport error throws `LibraCallException` with a null status.
- [X] T022 [P] [US1] Create `../service-cp-crime-results-enforcementgateway/src/test/java/uk/gov/hmcts/cp/controller/HearingResultedControllerTest.java` (`@WebMvcTest(HearingResultedController.class)`, `LibraClient` mocked):
  - a valid body → 200 and the body passed through unchanged;
  - missing `caseUrn`, or `resultCode: "XYZ"` → 400 `ErrorResponse`;
  - `LibraCallException(404, "E1", "not found")` → 502 with `details.libraStatus=404`, `details.errorCode="E1"`.
  Also create the **gateway integration test** `../service-cp-crime-results-enforcementgateway/src/test/java/uk/gov/hmcts/cp/integration/HearingResultedGatewayIntegrationTest.java` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, WireMock as APIM on a dynamic port, `cp.libra.apim-base-url` set via `@DynamicPropertySource`; constitution Principle VII). It covers:
  - a valid request → WireMock receives `POST /hearingResulted` with `Ocp-Apim-Subscription-Key`, and the 200 body is returned unchanged;
  - Libra 404 `{errorCode, errorDescription}` → 502 with the details;
  - a stub delay beyond `cp.libra.read-timeout-ms` (set to 500 in the test) → 502;
  - **no PII in logs** (Principle IV, FR-017): with `@ExtendWith(OutputCaptureExtension.class)`, the output must not contain the request's forename, surname, dateOfBirth, nationalInsuranceNumber, address1 or postcode, nor the stubbed response's `ctBankDetails` values.
- [X] T023 [P] [US1] Create `src/test/java/uk/gov/hmcts/cp/service/EnforcementCaseSelectorTest.java`: the GAPGD00 case is selected; a non-GAPGD00 case → `SkipReason.NO_ENFORCEMENT_CASE`; the authority code comes from config.
- [X] T024 [P] [US1] Create `src/test/java/uk/gov/hmcts/cp/client/ReferenceDataClientTest.java` (`MockRestServiceServer`):
  - GET `…/referencedata-query-api/query/api/rest/referencedata/result-definitions/{id}?on=2026-05-03` with the headers `Accept: application/vnd.referencedata.get-result-definition+json` and `CJSCPPUID` → `Optional.of(shortCode)`;
  - 404 → `Optional.empty()`;
  - a second call with the same `(id, on)` makes no HTTP request.
- [X] T025 [P] [US1] Create `src/test/java/uk/gov/hmcts/cp/service/ResultCodeResolverTest.java`:
  - results are gathered from `offences[].judicialResults`, `defendantCaseJudicialResults` and `hearing.defendantJudicialResults` (matching `masterDefendantId`);
  - renames: WC→DW, TFOUT→TFOOUT, WWDN→WDN;
  - `BPOCRFSD` is kept;
  - `PGPAY` is dropped;
  - duplicates are collapsed;
  - an unknown definition is dropped;
  - CP short codes are kept alongside the GOB codes.
- [X] T026 [P] [US1] Create `src/test/java/uk/gov/hmcts/cp/mapper/HearingResultedRequestMapperTest.java`:
  - the fixture maps to `src/test/resources/expected/hearing-resulted-request-minimum.json` (JSON-equal);
  - `caseUrn`/`prosecutorDefendantId` are unchanged;
  - forename, surname and address lines over 35 characters are truncated, and a postcode over 8 is truncated;
  - `paymentCardRequested`/`parentToPay`/`prisonSentenceIndicator` are "N";
  - with `HEARING_DATE`, `paymentDueDate` equals `dateOfHearing`;
  - `nowsDataRequest` is null.
  - **Schema test:** validate the serialised request against the `HearingResultedRequest` schema in `specs/001-cimd-4246-hearing-resulted-to-libra/docs/libra-gateway-hearing-events-openapi-v0.4.0.yml` using `com.networknt:json-schema-validator`. Load the YAML with Jackson YAML and resolve the `#/components/schemas` refs.
- [X] T027 [P] [US1] Create `src/test/java/uk/gov/hmcts/cp/client/EnforcementGatewayClientTest.java` (`MockRestServiceServer`): POST `{base}/hearingResulted` → 200 body → `GatewayResult.Success(response)`.
- [X] T028 [P] [US1] Create `src/test/java/uk/gov/hmcts/cp/messaging/HearingResultedEventListenerTest.java` (mocked `jakarta.jms.Message`): a valid body calls `HearingResultedProcessor.process` once; malformed JSON is logged and not rethrown.

### Implementation for User Story 1

- [X] T029 [P] [US1] Create `../service-cp-crime-results-enforcementgateway/src/main/java/uk/gov/hmcts/cp/client/LibraCallException.java`: a `RuntimeException` with `Integer libraStatus`, `String errorCode`, `String errorDescription`, plus a static factory that parses the Libra error JSON best-effort.
- [X] T030 [US1] Add `public HearingResultedResponse resultHearing(final HearingResultedRequest request)` to `../service-cp-crime-results-enforcementgateway/src/main/java/uk/gov/hmcts/cp/client/LibraClient.java`.
  - POST `/hearingResulted`, `MediaType.APPLICATION_JSON`, the same subscription-key header.
  - `.retrieve().onStatus(HttpStatusCode::isError, (req, res) -> throw LibraCallException.from(res))`, then `.toEntity(HearingResultedResponse.class).getBody()`.
  - `ResourceAccessException` → `LibraCallException(null, …)`.
  - Log `caseUrn` + status only.
  - Leave `confirmHearing` unchanged. (Depends on T029; makes T021 pass.)
- [X] T031 [US1] Create `../service-cp-crime-results-enforcementgateway/src/main/java/uk/gov/hmcts/cp/controller/HearingResultedController.java` (`@RestController implements EnforcementHearingApi`). Override only `postHearingResulted(@Valid HearingResultedRequest)` → `ResponseEntity.ok(libraClient.resultHearing(request))`. (Depends on T030.)
- [X] T032 [US1] Create `../service-cp-crime-results-enforcementgateway/src/main/java/uk/gov/hmcts/cp/controller/GlobalExceptionHandler.java` (`@RestControllerAdvice`):
  - `MethodArgumentNotValidException` / `HttpMessageNotReadableException` → 400 `ErrorResponse{error:"INVALID_PAYLOAD"}`;
  - `LibraCallException` → 502 `ErrorResponse{error:"LIBRA_CALL_FAILED", message, details{libraStatus, errorCode, errorDescription}, timestamp, traceId}`.
  Makes T022 pass.
- [X] T033 [P] [US1] Create `src/main/java/uk/gov/hmcts/cp/service/SkipReason.java` (enum `RESHARE, NO_ENFORCEMENT_CASE, MULTIPLE_ENFORCEMENT_CASES, MULTIPLE_DEFENDANTS, LINKED_APPLICATION`) and `src/main/java/uk/gov/hmcts/cp/service/EnforcementCaseSelector.java`.
  - It returns `SelectionResult` (a sealed interface: `Selected(ProsecutionCase, Defendant)` | `Skipped(SkipReason)`).
  - US1 covers the enforcement filter: `prosecutionAuthorityOUCode.equals(${cp.enforcement.authority-code})`.
  - With exactly one enforcement case and one defendant, return `Selected`.
  Makes T023 pass.
- [X] T034 [P] [US1] Create `src/main/java/uk/gov/hmcts/cp/client/ResultDefinition.java` (record `shortCode`, `@JsonIgnoreProperties(ignoreUnknown = true)`) and `src/main/java/uk/gov/hmcts/cp/client/ReferenceDataClient.java`, per `contracts/reference-data-result-definition.md`.
  - Base URL is `cp.reference-data.base-url`; the CJSCPPUID header comes from `cp.reference-data.cjscppuid`. Built from `referenceDataRestClientBuilder`.
  - Cache: `ConcurrentHashMap<CacheKey(UUID, LocalDate), Optional<String>>`.
  - 404 (`HttpClientErrorException.NotFound`) → empty.
  - Add the `cp.reference-data.*` keys to `application.yaml`.
  Makes T024 pass.
- [X] T035 [US1] Create `src/main/java/uk/gov/hmcts/cp/service/ResultCodeResolver.java` returning `ResolvedCodes(List<String> cpShortCodes, Set<HearingResult.ResultCodeEnum> gobCodes, List<String> droppedCodes)`, per research.md R4-R6 and R16.
  - The lookup date is `orderedDate`, falling back to the event's `hearingDay`.
  - Renames come from `HearingResultProperties.resultCodeRenames`.
  - Enum match uses `ResultCodeEnum.fromValue` inside try/catch; unmatched codes are dropped.
  - `gobCodes` is a `LinkedHashSet`.
  (Depends on T034; makes T025 pass.)
- [X] T036 [US1] Create `src/main/java/uk/gov/hmcts/cp/mapper/Truncate.java` (null-safe `toMaxLength(String, int)`), `src/main/java/uk/gov/hmcts/cp/mapper/MappingResult.java` (sealed: `Mapped(HearingResultedRequest)` | `Failed(MappingFailureReason, String detail)`), `src/main/java/uk/gov/hmcts/cp/mapper/MappingFailureReason.java` (`CASE_URN_INVALID, PROSECUTOR_DEFENDANT_ID_MISSING, ADDRESS1_MISSING, PAYMENT_DUE_DATE_UNAVAILABLE, ORGANISATION_DEFENDANT_NOT_SUPPORTED_YET`), and `src/main/java/uk/gov/hmcts/cp/mapper/HearingResultedRequestMapper.java`.
  - The mapper implements the data-model.md §2 table, using the generated `uk.gov.hmcts.cp.openapi.model` builders.
  - `caseUrn` and `prosecutorDefendantId` are never truncated: blank or longer than 36 → `Failed`.
  - `legalEntityDefendant` present → `Failed(ORGANISATION_DEFENDANT_NOT_SUPPORTED_YET)`.
  - It takes `Optional<NowsDataRequest>` as a parameter; `Optional.empty()` in US1.
  Makes T026 pass.
- [X] T037 [P] [US1] Create `src/main/java/uk/gov/hmcts/cp/client/GatewayResult.java` (sealed: `Success(HearingResultedResponse)` | `Failure(Integer httpStatus, String detail)`) and `src/main/java/uk/gov/hmcts/cp/client/EnforcementGatewayClient.java`.
  - POST `${cp.enforcement-gateway.base-url}/hearingResulted` using `enforcementGatewayRestClientBuilder` from T018.
  - 2xx → `Success`.
  - Any `RestClientResponseException` → `Failure(status, responseBody)`.
  - `ResourceAccessException` → `Failure(null, message)`.
  - It never throws.
  - Add the `cp.enforcement-gateway.base-url` key.
  Makes T027 pass.
- [X] T038 [US1] Create `src/main/java/uk/gov/hmcts/cp/service/SubmissionStore.java` with:
  - `UUID recordSending(UUID hearingId, UUID caseId, UUID defendantId, String caseUrn, Instant sharedTime, String requestJson)`;
  - `void recordSucceeded(UUID id, String responseJson, int httpStatus)`.
  Each method is its own `@Transactional`. (Depends on T011.)
- [X] T039 [US1] Create `src/main/java/uk/gov/hmcts/cp/service/HearingResultedProcessor.java` `process(HearingResultedEvent)`: selector → resolver → mapper (NOWS empty) → `recordSending` → `gatewayClient.submit` → `recordSucceeded`.
  - WARN if `response.caseUrn` ≠ `request.caseUrn`.
  - At this stage a `Skipped`, a `Failed` mapping, empty `gobCodes` or a gateway `Failure` is **logged only**; US2/US4 add the rest.
  - Structured INFO logs carry `hearingId`, `caseId`, `defendantId`, `caseUrn` and the outcome, with no payload.
  (Depends on T033-T038.)
- [X] T040 [US1] *(Follow-up from T078: the listener now uses `containerFactory = HearingResultedJmsConfig.CONTAINER_FACTORY` with its own client id, R23.)* Create `src/main/java/uk/gov/hmcts/cp/messaging/HearingResultedEventListener.java` (`@Profile("docker")`, `@JmsListener(destination=${cp.messaging.public-event-topic}, subscription=${cp.messaging.hearing-resulted-subscription-name}, selector=${cp.messaging.hearing-resulted-selector})`).
  - `objectMapper.readValue(message.getBody(String.class), HearingResultedEvent.class)` → `processor.process`.
  - A broad `RuntimeException` catch logs `CPPNAME`/`jmsMessageId` (the gateway `HearingAllocationEventListener` pattern).
  - Add the `cp.messaging.hearing-resulted-*` keys to `application.yaml`.
  Makes T028 pass.
- [X] T041 [US1] *(Also added `src/test/java/uk/gov/hmcts/cp/service/HearingResultedProcessorTest.java`, branch unit tests for the orchestrator, per Principle VII.)* Create `src/test/java/uk/gov/hmcts/cp/integration/HearingResultedFlowIntegrationTest.java` (extends `IntegrationTestBase`, WireMock on a dynamic port for reference data and gateway, properties via `@DynamicPropertySource`). It calls `HearingResultedProcessor.process` with the fixtures:
  - enforcement → one POST whose body equals `expected/hearing-resulted-request-minimum.json`, and one `SUCCEEDED` row with `response_payload`;
  - non-enforcement → no POST, no row;
  - **no PII in logs (FR-017):** with `@ExtendWith(OutputCaptureExtension.class)`, the captured output of the enforcement run must not contain the fixture's forename, surname, date of birth, NI number, `address1` or postcode.
  (Depends on T039.)

**Checkpoint**: US1 is fully functional. `./gradlew build` is green in W and G (PMD clean). MVP demo against WireMock, or the APIM mock once T007 is delivered.

---

## Phase 4: User Story 2 - Only first-time, in-scope results are sent (Priority: P2)

**Goal**: reshares, repeated deliveries and out-of-scope shapes never reach GOB, and every skip is traceable (FR-003, 004, 005).

**Independent Test**: T042 plus T046. The reshare, two-defendant, two-case and linked-application fixtures, and a repeated enforcement fixture, produce 0 extra POSTs, with a log reason for each.

### Tests for User Story 2 ⚠️

- [X] T042 [P] [US2] Extend `src/test/java/uk/gov/hmcts/cp/service/EnforcementCaseSelectorTest.java` with one test per reason: `RESHARE` (checked first, even for GAPGD00), `MULTIPLE_ENFORCEMENT_CASES`, `MULTIPLE_DEFENDANTS`, `LINKED_APPLICATION`, using the T016 fixtures.
- [X] T043 [P] [US2] Create `src/test/java/uk/gov/hmcts/cp/service/SubmissionStoreIntegrationTest.java` (extends `IntegrationTestBase`): `findExisting` returns the row after `recordSending` (not stale while in flight), a final row is never stale and an old SENDING row is; a concurrent duplicate `recordSending` returns `Optional.empty()` instead of throwing.

### Implementation for User Story 2

- [X] T044 [US2] Extend `src/main/java/uk/gov/hmcts/cp/service/EnforcementCaseSelector.java` in the order from research.md R2/R3:
  1. `isReshare` → `RESHARE` (INFO);
  2. no enforcement case → `NO_ENFORCEMENT_CASE` (DEBUG);
  3. more than one → `MULTIPLE_ENFORCEMENT_CASES` (WARN);
  4. more than one defendant → `MULTIPLE_DEFENDANTS` (WARN);
  5. any `hearing.courtApplications[].courtApplicationCases[].prosecutionCaseId` equals `case.id` → `LINKED_APPLICATION` (WARN).
  Each log line carries `hearingId` and `caseUrn`. Makes T042 pass.
- [X] T045 [US2] *(`recordSending` is deliberately not `@Transactional`: the unique-key violation is translated after the repository's own transaction rolls back. Added `ExistingSubmission` and a `Clock` bean (`config/ClockConfig.java`) for the stale check.)* Change `src/main/java/uk/gov/hmcts/cp/service/SubmissionStore.java`:
  - add `Optional<ExistingSubmission> findExisting(UUID hearingId, UUID caseId, UUID defendantId)` (id, status, updatedAt) and `boolean isStale(ExistingSubmission)`: true only for a `SENDING` row older than `HearingResultProperties.staleSendingThreshold` (T019; research.md R19);
  - make `recordSending` return `Optional<UUID>`, catching `DataIntegrityViolationException` → `Optional.empty()`.
  Makes T043 pass.
- [X] T046 [US2] Update `src/main/java/uk/gov/hmcts/cp/service/HearingResultedProcessor.java`:
  - log the skip reason and return on `Skipped`;
  - after selection, if a row already exists (`findExisting`) → INFO "already submitted" and return, or, for a stale SENDING row (`isStale`), mark it interrupted (R19) and return; *(2026-09-29 review: the unused `alreadySubmitted` helper was removed; this is the path the processor runs)*
  - if `recordSending` returns empty → INFO and return.
  Extend `HearingResultedFlowIntegrationTest` with the reshare, two-defendant, two-case, linked-application and processed-twice scenarios, each asserting 0 extra POSTs and rows (quickstart scenarios 2-5).

**Checkpoint**: US1 and US2 both work. Reprocessing any fixture never produces a second POST.

---

## Phase 5: User Story 3 - GOB returns the notice data needed for the results given (Priority: P2)

**Goal**: `nowsDataRequest` holds the de-duplicated union of the `NowsDataItemName` values for the resulted short codes, and is left out when the union is empty (FR-010, FR-011).

**Independent Test**: T047 plus T050. Test config maps `SC → [Account Balance, Account Number]` and `CW → [Account Number, Account Warrant Number]`. The fixture with SC and CW sends 3 unique items. A fixture with unmapped codes sends no `nowsDataRequest`.

### Tests for User Story 3 ⚠️

- [X] T047 [P] [US3] Create `src/test/java/uk/gov/hmcts/cp/service/NowsDataItemSelectorTest.java`:
  - empty config → `Optional.empty()`;
  - overlapping codes → a unique union in insertion order;
  - lookup by CP short code;
  - codes with no mapping are ignored.
- [X] T048 [P] [US3] Create `src/test/java/uk/gov/hmcts/cp/config/HearingResultPropertiesValidationTest.java` (`ApplicationContextRunner`): `nows-data-items-by-short-code.SC=[Not A Real Item]` → context fails; `[Account Balance]` → loads.

### Implementation for User Story 3

- [X] T049 [US3] Create `src/main/java/uk/gov/hmcts/cp/service/NowsDataItemSelector.java` `Optional<NowsDataRequest> select(List<String> cpShortCodes)`. It builds `NowsDataRequest` with a `LinkedHashSet<NowsDataItemRequest>` of names parsed via `NowsDataItemName.fromValue`. Makes T047 pass.
- [X] T050 [US3] Add validation to `src/main/java/uk/gov/hmcts/cp/config/HearingResultProperties.java`: an `@PostConstruct` or `@AssertTrue` check that every configured name parses with `NowsDataItemName.fromValue`. Makes T048 pass.
- [X] T051 [US3] *(Integration tests: `NowsDataRequestIntegrationTest` with profile `nowsmapping-test`, and a no-mapping case in `HearingResultedFlowIntegrationTest`. The WireMock stubs moved to the shared `WorkflowStubsIntegrationTestBase`.)* Wire `NowsDataItemSelector` into `src/main/java/uk/gov/hmcts/cp/service/HearingResultedProcessor.java`, passing its result to the mapper.
  - Add `src/test/resources/application-nowsmapping-test.yaml` with the mapping above.
  - Extend `HearingResultedFlowIntegrationTest`: with the mapping, the posted body has 3 items; without it, `nowsDataRequest` is absent (quickstart scenario 9).
  - When WireMock returns `nowsDataItems: {}`, the row is `SUCCEEDED` with `{}` stored.

**Checkpoint**: US1-US3 work. The mapping rows can later be added in config only (research.md R7).

---

## Phase 6: User Story 4 - Failed or unsendable submissions are visible for follow-up (Priority: P3)

**Goal**: every attempt has a recorded outcome: `FAILED`, `MAPPING_FAILED` or `SKIPPED_NO_RESULT_CODE`, with a reason. Calls are bounded in time, and there are no retries (FR-013, FR-014).

**Independent Test**: T052 plus T057. A gateway 502, a gateway timeout, and the missing-account-number and unknown-codes-only fixtures each produce the matching status row with a reason. A later valid event still succeeds.

### Tests for User Story 4 ⚠️

- [X] T052 [P] [US4] Extend `src/test/java/uk/gov/hmcts/cp/client/EnforcementGatewayClientTest.java`: 400 → `Failure(400, body)`; 502 → `Failure(502, body)`; read timeout → `Failure(null, "TIMEOUT – outcome at GOB unknown")`; no exception escapes. Uses `enforcementGatewayRestClientBuilder` (research.md R20).
- [X] T053 [P] [US4] Extend `src/test/java/uk/gov/hmcts/cp/service/SubmissionStoreIntegrationTest.java`:
  - `recordFailed` sets status, `http_status` and `error_detail`;
  - `recordMappingFailed` / `recordSkippedNoResultCode` insert rows with a null `request_payload` and a reason;
  - `findStaleSending(cutoff)` returns only `SENDING` rows older than the cutoff;
  - `markInterrupted` sets `FAILED` + `"INTERRUPTED – outcome at GOB unknown"`.
  Also create `src/test/java/uk/gov/hmcts/cp/service/StaleSubmissionSweeperTest.java`: with a fixed `Clock`, a stale row is marked and a fresh row is untouched.
- [X] T054 [P] [US4] Create `../service-cp-crime-results-enforcementgateway/src/test/java/uk/gov/hmcts/cp/config/LibraRestClientTimeoutTest.java`: the `libraRestClientBuilder` bean applies `cp.libra.read-timeout-ms` (a WireMock delay beyond the timeout → `ResourceAccessException`), and the defaults are 5000/40000, so the total stays below the workflow's 50s read timeout (research.md R20).

### Implementation for User Story 4

- [X] T055 [US4] Add these to `src/main/java/uk/gov/hmcts/cp/service/SubmissionStore.java`:
  - `recordFailed(UUID id, Integer httpStatus, String detail)`;
  - `recordMappingFailed(ids…, caseUrn, sharedTime, MappingFailureReason, detail)`;
  - `recordSkippedNoResultCode(ids…, caseUrn, sharedTime, List<String> droppedCodes)`.
  Reuse the unique key, so a redelivered failing event isn't recorded twice.
  Also add `List<UUID> findStaleSending(Instant cutoff)` and `markInterrupted(UUID id)` (research.md R19), and add `@Query`-backed methods to `src/main/java/uk/gov/hmcts/cp/repository/HearingResultSubmissionRepository.java`. Makes T053 pass.
- [X] T056 [US4] *(The processor now branches on `findExisting` (fresh → skip, stale → `markInterrupted`), and `markInterrupted` updates only while still SENDING (`failIfSending`). A caseUrn that is too long isn't stored; the row keeps an empty value (Principle VI).)* Update `src/main/java/uk/gov/hmcts/cp/service/HearingResultedProcessor.java`:
  - empty `gobCodes` → `recordSkippedNoResultCode`;
  - `MappingResult.Failed` → `recordMappingFailed`;
  - `GatewayResult.Failure` → `recordFailed`;
  - no retry, and the method always returns normally.
  Keep the existing `findExisting` short-circuit, so every status is terminal (per the research.md R12 interim rule).
  - Existing **stale** `SENDING` row → `markInterrupted`, WARN, return **without POST** (R19; open with the BA/GOB).
  - Create `src/main/java/uk/gov/hmcts/cp/service/StaleSubmissionSweeper.java` (`@Scheduled(fixedDelayString = "${cp.hearing-result.stale-sending-sweep-interval}")`, using an injected `Clock`), add `@EnableScheduling` on `src/main/java/uk/gov/hmcts/cp/Application.java`, reading the threshold from `HearingResultProperties` (the keys themselves are added in T019).
- [X] T057 [US4] *(Done early, 2026-09-27, in US1: the T022 gateway integration test needs the Libra timeouts.)* In G, add bounded timeouts to `libraRestClientBuilder` in `../service-cp-crime-results-enforcementgateway/src/main/java/uk/gov/hmcts/cp/config/RestClientConfig.java`: `cp.libra.connect-timeout-ms` (5000) and `cp.libra.read-timeout-ms` (40000) (research.md R20), with a JDK `HttpClient` + `JdkClientHttpRequestFactory` as in the first bean. Add the keys to `../service-cp-crime-results-enforcementgateway/src/main/resources/application.yaml`. Makes T054 pass.
- [X] T058 [US4] *(Implemented as `FailureOutcomesIntegrationTest` (gateway read timeout 500 ms) and `PaymentDueDateNoneIntegrationTest`. The stand-in setting moved to `@TestPropertySource` on the base class so subclasses can override it.)* Extend `src/test/java/uk/gov/hmcts/cp/integration/HearingResultedFlowIntegrationTest.java`:
  - gateway 502 → `FAILED` with `http_status=502`;
  - WireMock delay beyond `cp.enforcement-gateway.read-timeout-ms` (set to 500 in the test) → `FAILED` with a null `http_status` and `"TIMEOUT – outcome at GOB unknown"`; the same event redelivered → no POST;
  - missing account number → `MAPPING_FAILED`/`PROSECUTOR_DEFENDANT_ID_MISSING`;
  - unknown codes only → `SKIPPED_NO_RESULT_CODE`;
  - `payment-due-date-fallback=NONE` → `MAPPING_FAILED`/`PAYMENT_DUE_DATE_UNAVAILABLE`;
  - a pre-inserted stale `SENDING` row, then the same event again → the row becomes `FAILED` (INTERRUPTED) and **no POST** is made;
  - a following valid event still succeeds.
  These are quickstart scenarios 6-8.

**Checkpoint**: iteration 1 is complete (US1-US4). Every quickstart scenario 1-9 passes locally.

---

## Phase 7: User Story 5 - Complete defendant and result details (Priority: P3)

**Goal**: the payload refinements from CIMD-4247, 4248, 4253 and 4255.

**Independent Test**: T065. The individual, organisation and CW/SC fixtures produce payloads matching the story examples, and pass the schema test from T026.

**Prerequisite**: record the BA answers for each open point in `research.md` (new R19+ rows) **before** the matching implementation task.

- [ ] T059 [US5] Record the BA rulings in `specs/001-cimd-4246-hearing-resulted-to-libra/research.md`:
  - 4247: a telephone shorter than 10 characters is dropped or fails the mapping; address `*` splitting; AC3 "block dispatch" = `MAPPING_FAILED`;
  - 4248 vs 4251: parent-to-pay modelling;
  - 4253: SC → `prisonSentenceIndicator=Y`? CWN jailDays?;
  - 4255 vs 4246: the result filter rule.
  Update spec.md US5 acceptance scenarios if anything changes (run `/speckit-clarify`).
- [ ] T060 [P] [US5] Add tests to `src/test/java/uk/gov/hmcts/cp/mapper/HearingResultedRequestMapperTest.java` for the 4247 rules from T059 (telephone min length, `*` address split, NINO ≤ 9), and create `src/test/java/uk/gov/hmcts/cp/mapper/OrganisationDefendantMappingTest.java` using `hearing-resulted-organisation-defendant.json`.
- [ ] T061 [US5] Implement the 4247 individual rules in `src/main/java/uk/gov/hmcts/cp/mapper/HearingResultedRequestMapper.java`, and extract `src/main/java/uk/gov/hmcts/cp/mapper/DefendantDetailsMapper.java` (individual + organisation).
- [ ] T062 [US5] Implement 4248 in `src/main/java/uk/gov/hmcts/cp/mapper/DefendantDetailsMapper.java`: `organisationName`, org `address1..5`/`postcode` and `workTelephoneNumber` from `legalEntityDefendant.organisation`, with no personal fields. Remove `ORGANISATION_DEFENDANT_NOT_SUPPORTED_YET` from `MappingFailureReason`.
- [ ] T063 [P] [US5] Create `src/test/java/uk/gov/hmcts/cp/mapper/EnforcementDetailsMapperTest.java` and `src/main/java/uk/gov/hmcts/cp/mapper/EnforcementDetailsMapper.java` (4253):
  - `prisonSentenceIndicator` = Y when the GOB codes contain CW (and SC, if T059 confirms), otherwise N;
  - `results[].jailDays` is parsed from the jail-days prompt value (display string → integer days, ≤ 99999). Add the prompt reference to `HearingResultProperties`.
- [ ] T064 [US5] Apply the T059 result-filter ruling in `src/main/java/uk/gov/hmcts/cp/service/ResultCodeResolver.java`, and hold the ENF/CONF/BOTH classification in `HearingResultProperties` if the ruling needs it. Update the `ResultCodeResolverTest` expectations.
- [ ] T065 [US5] Extend `src/test/java/uk/gov/hmcts/cp/integration/HearingResultedFlowIntegrationTest.java` with the organisation and CW/SC scenarios, asserting against new `src/test/resources/expected/*.json` files taken from the 4247/4248/4253 story examples.

**Checkpoint**: iteration 2 is complete. The minimum payload is replaced by the agreed defendant and result detail.

---

## Phase 8: User Story 6 - Remaining payment, employer, guardian and enforcement details (Priority: P4)

**Goal**: the optional blocks from CIMD-4249, 4250, 4251, 4252, 4254 and 4256.

**Independent Test**: T074. Each story's example scenario produces its block exactly, and the full payload passes the schema test.

**Prerequisite**: each task's open point is recorded in `research.md` first (see plan.md §5 and research.md §2).

- [ ] T066 [US6] Record the BA/GOB rulings in `specs/001-cimd-4246-hearing-resulted-to-libra/research.md`:
  - `paymentDueDate` without PAYT (v0.5.0 optional?);
  - 4249 employerReference mandatory vs optional;
  - 4250 name length 35 vs 30;
  - 4252 `nextHearingReason` and multiple ACNOTEs;
  - 4254 CTCDCFIL vs CTENCFIL;
  - 4256 enforcer codes incl. BWTD/CW.
- [ ] T067 [P] [US6] Create `src/test/java/uk/gov/hmcts/cp/mapper/EmployerDetailsMapperTest.java` and `src/main/java/uk/gov/hmcts/cp/mapper/EmployerDetailsMapper.java` (4249): only for AEO/AEOC(/PGPAY), from `personDefendant.employerOrganisation` and `employerPayrollReference`. Truncations: name 35, reference 20, address 32, email 76. The block is omitted when there's no employer.
- [ ] T068 [P] [US6] Create `src/test/java/uk/gov/hmcts/cp/mapper/ParentGuardianDetailsMapperTest.java` and `src/main/java/uk/gov/hmcts/cp/mapper/ParentGuardianDetailsMapper.java` (4250): `address1` is mandatory when a name is present, with the T066 length ruling.
- [ ] T069 [P] [US6] Create `src/test/java/uk/gov/hmcts/cp/mapper/PaymentTermsMapperTest.java` and `src/main/java/uk/gov/hmcts/cp/mapper/PaymentTermsMapper.java` (4251): the PAYT/INSTL/LUMSI/PDATE/PTFOR prompts → `paymentTerms`, `paymentDueDate`, `instalmentAmount`, `lumpSum`; amounts are parsed from display strings (e.g. "£60" → 60.00). Then apply the T066 `paymentDueDate` ruling. If GOB made it optional, delete `PaymentDueDateFallback` from `HearingResultProperties` and the `HEARING_DATE` branch in `HearingResultedRequestMapper`.
- [ ] T070 [P] [US6] Create `src/test/java/uk/gov/hmcts/cp/mapper/NextHearingMapperTest.java` and `src/main/java/uk/gov/hmcts/cp/mapper/NextHearingMapper.java` (4252): `enforcement.nextHearingDate/CourtCode/Reason` and `accountNotes` (≤ 28 characters), for AEC/BWTD/SUMM/CWN, with the court code as the National Courthouse code per T066.
- [ ] T071 [P] [US6] Create `src/test/java/uk/gov/hmcts/cp/mapper/TransferLjaMapperTest.java` and `src/main/java/uk/gov/hmcts/cp/mapper/TransferLjaMapper.java` (4254): `enforcement.transferLjaCode` for TFOOUT, from the transfer prompt.
- [ ] T072 [P] [US6] Create `src/test/java/uk/gov/hmcts/cp/mapper/EnforcerCodeMapperTest.java` and `src/main/java/uk/gov/hmcts/cp/mapper/EnforcerCodeMapper.java` (4256): `results[].enforcerCode` for the warrant codes (the list from T066), from the enforcer prompt (CIMD-4336). ABDC defaults to 500 (CIMD-4335).
- [ ] T073 [US6] Wire T067-T072 into `src/main/java/uk/gov/hmcts/cp/mapper/HearingResultedRequestMapper.java`, in the order of the `HearingResultedRequest` fields.
- [ ] T074 [US6] Extend `src/test/java/uk/gov/hmcts/cp/integration/HearingResultedFlowIntegrationTest.java` with one scenario per sibling story, using new fixtures in `src/test/resources/events/` and expected payloads in `src/test/resources/expected/`, all passing the T026 schema test.

**Checkpoint**: iteration 3 is complete. Every block in the GOB contract is populated when the results call for it.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: documentation, local tooling, contract sync and hardening (plan.md iteration 4+).

- [X] T075 [P] Update `README.md` in W: responsibilities (link spec.md and plan.md), the new env vars (`DATASOURCE_*`, `REFERENCE_DATA_URL`, `REFERENCE_DATA_CJSCPPUID`, `ENFORCEMENT_GATEWAY_URL`, `ENFORCEMENT_AUTHORITY_CODE`, `PAYMENT_DUE_DATE_FALLBACK`), and the local run steps from quickstart.md.
- [X] T076 [P] Update `../service-cp-crime-results-enforcementgateway/README.md`: the new inbound `POST /hearingResulted`, the 400/502 semantics, the timeout keys, and the access-control model (FR-018, research.md R21): network isolation now, bearer auth later. Link `docs/InboundAccess.md` (T085).
- [X] T077 [P] *(The stubs are in top-level `wiremock/mappings/`, not `docker/wiremock/`: the Dockerfile runs `COPY docker/* /app/`, which would ship them in the image. Compose profile `stubs`, port 8089.)* Add an optional `wiremock` service to `docker-compose.yml`, with stubs in `docker/wiremock/mappings/reference-data-result-definition.json` and `docker/wiremock/mappings/gateway-hearing-resulted.json`, so W runs locally without real backends.
- [X] T078 *(2026-09-27: over a real Artemis broker, with the real gateway and WireMock as APIM and reference data. This found and fixed the JMS client-id clash (research.md R23, `HearingResultedJmsConfig`).)* Run every quickstart.md step and verification scenario 1-9 end to end, and record the results in `specs/001-cimd-4246-hearing-resulted-to-libra/quickstart.md` (a "Last validated" line).
- [X] T079 *(2026-09-27: `build pmdMain jacocoTestReport` green in W (110 tests) and G (36). Hand-written code is PMD-clean everywhere. Open notes: (1) `pmdTest` is disabled by the template in W and G (`gradle/pmd.gradle`), which the constitution VII wording (pmdMain *and* pmdTest) needs a decision on; (2) the API repo `pmdMain` reports 1921 violations, all in generated `build/generated` code (not run in its CI).)* Run `./gradlew build pmdMain pmdTest jacocoTestReport` in W, G and A, and fix every PMD violation.
- [ ] T080 [P] When Libra publishes contract v0.5.0:
  - replace `specs/001-cimd-4246-hearing-resulted-to-libra/docs/libra-gateway-hearing-events-openapi-v0.4.0.yml` (removing the local amendment comment);
  - re-copy the schema block into `../api-cp-crime-results-enforcementgateway/src/main/resources/openapi/openapi-spec.yml`;
  - bump the API jar version in W and G `build.gradle`;
  - re-run the T026 schema test.
- [ ] T081 [P] Replace the per-id cache in `src/main/java/uk/gov/hmcts/cp/client/ReferenceDataClient.java` with a bulk `cacheable` result-definition load and a scheduled refresh (research.md R4b).
- [ ] T082 [P] `X-Correlation-ID`: add an optional header parameter to `postHearingResulted` in A's `openapi-spec.yml`; generate it in `EnforcementGatewayClient` (W); forward it in `LibraClient.resultHearing` (G); persist it in a new `V1.002__add_correlation_id_to_hearing_result_submission.sql`.
- [ ] T083 [P] Retention: add `expires_at` and a purge job (PCR `cp_version.expires_at` pattern) in `V1.00N__add_expires_at_to_hearing_result_submission.sql` and `src/main/java/uk/gov/hmcts/cp/service/SubmissionPurgeJob.java`, once the retention period is agreed.
- [ ] T084 Update `specs/001-cimd-4246-hearing-resulted-to-libra/docs/CIMD-4246-gap-analysis.md` and `specs/001-cimd-4246-hearing-resulted-to-libra/research.md` §2 as open items close; update `specs/001-cimd-4246-hearing-resulted-to-libra/adrs/001-…` when encryption is scheduled.
- [X] T085 [P] Create `../service-cp-crime-results-enforcementgateway/docs/InboundAccess.md` for the platform/deploy team (FR-018, research.md R21):
  - the gateway must have **no ingress or external route** for `/hearingResulted`;
  - a sample Kubernetes `NetworkPolicy` admitting traffic to the gateway pod's port 8082 only from pods labelled as `service-cp-crime-results-enforcementworkflow`;
  - how to verify it: a call from another pod is refused, and a call from the workflow pod succeeds.
  Required before any environment beyond local receives the gateway change.
- [ ] T086 Once the tech lead confirms the later step of research.md R21, add service-to-service bearer auth:
  - in G, create `../service-cp-crime-results-enforcementgateway/src/main/java/uk/gov/hmcts/cp/auth/` (EntraTokenValidator, AuthorizationPolicy, EntraAuthProperties, AuthMode), modelled on `../service-cp-crime-results-pcr/src/main/java/uk/gov/hmcts/cp/auth/` and `../service-cp-crime-results-pcr/docs/Authentication.md`, protecting only `/hearingResulted`;
  - in W, add token acquisition (managed identity) to `src/main/java/uk/gov/hmcts/cp/client/EnforcementGatewayClient.java`;
  - add tests in both repos (valid token → 200, missing/invalid/wrong caller → 401/403).
- [X] T087 [P] Add a **Support runbook** section to `README.md` in W (spec.md SC-004, Assumptions "Support access"):
  - read-only SQL to find a defendant's submission by `case_urn`, `hearing_id` or `defendant_id`: status, `http_status`, `error_detail`, `created_at`/`updated_at`;
  - listing `FAILED` rows with `error_detail LIKE 'INTERRUPTED%'` or `'TIMEOUT%'` (outcome at GOB unknown, R19/R20);
  - what each status means (data-model.md §3 FR-013 mapping);
  - a warning never to select `request_payload`/`response_payload` into shared channels (PII).

- [ ] T088 Decide the replica model for the workflow's JMS consumer (research.md open item 13). Either document "single replica" in the deploy configuration, or switch `HearingResultedJmsConfig` to a JMS 2.0 shared durable subscription (`factory.setSubscriptionShared(true)`, no client id) and test it with two instances. The same applies to the gateway's listeners (open item 11).
- [ ] T089 [P] Update `../service-cp-crime-results-enforcementgateway/docs/InboundAccess.md` once the platform team chooses between a separate actuator management port and extra ingress rules for monitoring/probes (research.md open item 15). If a management port is chosen, set `management.server.port` in `../service-cp-crime-results-enforcementgateway/src/main/resources/application.yaml`.
- [ ] T090 Do not enable the hearing-resulted flow in production until research.md open item 12 is resolved (T069 payment terms mapping, or the BA/GOB ruling on `paymentDueDate`). Record the go-live decision in `specs/001-cimd-4246-hearing-resulted-to-libra/research.md`.

### Integration test suite (cross-cutting; later stories extend it; research.md R25)

- [X] T091 [P] In `build.gradle`, add `testImplementation 'org.apache.artemis:artemis-jakarta-server'` (an embedded broker for tests). Create `src/test/java/uk/gov/hmcts/cp/integration/EmbeddedBrokerIntegrationTestBase.java`: it extends `WorkflowStubsIntegrationTestBase`, activates the `docker` profile so the real listeners run, and sets `spring.artemis.mode=embedded` with a non-persistent broker.
- [X] T092 Create `src/test/java/uk/gov/hmcts/cp/integration/JmsHearingResultedIntegrationTest.java`. It covers:
  - every JMS listener container is running (catches a client-id clash such as R23);
  - an event published to `public.event` with `CPPNAME=public.events.hearing.hearing-resulted` → a SUCCEEDED row and one gateway POST;
  - an event with another `CPPNAME` is not consumed by the hearing-resulted listener;
  - a malformed message is logged without the body and does not stop the next valid one.
- [X] T093 [P] Create `src/test/java/uk/gov/hmcts/cp/support/GatewayContract.java`, validating JSON against the **gateway** contract (`openapi/openapi-spec.yml` inside the `api-cp-crime-results-enforcementgateway` jar). Share the OpenAPI-schema loading with `LibraContract` in `src/test/java/uk/gov/hmcts/cp/support/OpenApiSchemas.java`.
- [X] T094 *(2026-09-29: 12 scenarios green. Scenario 12 found a defect: a 2xx reply that is not JSON could not be stored in the jsonb column, so the row stayed SENDING. Fixed in `SubmissionStore.recordSucceeded`, research.md R24a.)* Create the scenario runner `src/test/java/uk/gov/hmcts/cp/integration/ScenarioIntegrationTest.java` and scenario folders `src/test/resources/scenarios/<NN-name>/scenario.json` (optional `expected-request.json`). Each scenario is published through the embedded broker (T091). Every request received is checked against both contracts (T093), and every stubbed 2xx reply against the gateway `HearingResultedResponse`. Add `ReferenceDataClient.clearCache()` in `src/main/java/uk/gov/hmcts/cp/client/ReferenceDataClient.java` so each scenario starts with an empty cache. Cover every current behaviour: success with NOWS items, the reshare, three out-of-scope shapes, non-enforcement, unknown codes only, missing account number, gateway 502, reference data 503, gateway timeout, and an unparsable 2xx. **Later stories add a folder** (US5/US6 payload blocks, v0.5.0 contract changes) instead of new test code.
- [X] T095 [P] Document how to add a scenario (folder layout, fields) in `specs/001-cimd-4246-hearing-resulted-to-libra/quickstart.md` and `README.md`.

### Gateway integration test suite (G; research.md R25, extended to G)

Both of G's flows: `POST /hearingResulted` (this feature) and the earlier event-driven `confirmedHearing` flow (`public.listing.hearing-confirmed`/`hearing-updated` → Progression lookup → APIM `POST /confirmedHearing`), which had no integration test.

- [X] T096 [P] In `../service-cp-crime-results-enforcementgateway/build.gradle`, add `testImplementation 'org.apache.artemis:artemis-jakarta-server'`. Create `../service-cp-crime-results-enforcementgateway/src/test/java/uk/gov/hmcts/cp/integration/GatewayIntegrationTestBase.java`: the `docker` profile with an embedded, non-persistent Artemis broker, one WireMock server standing in for both APIM and the Progression query API, `MockMvc`, a `publish(cppName, body)` helper, and a marker helper that proves earlier messages were processed (G stores nothing, so the marker is a confirmed hearing whose APIM callback the test waits for).
- [X] T097 *(2026-09-29: reproduced research.md open item 11, `InvalidClientIDException`; fixed with `HearingAllocationJmsConfig`, R26.)* Create `../service-cp-crime-results-enforcementgateway/src/test/java/uk/gov/hmcts/cp/integration/JmsListenersIntegrationTest.java`: every listener container is connected (research.md open item 11), `hearing-confirmed` → one APIM callback, `hearing-listed` is not consumed by the allocation listener, and a malformed message does not stop the next one.
- [X] T098 Create the scenario runner `../service-cp-crime-results-enforcementgateway/src/test/java/uk/gov/hmcts/cp/integration/HearingConfirmedScenarioIntegrationTest.java` with folders under `src/test/resources/scenarios/hearing-confirmed/`: the event (confirmed or updated), Progression answers per case id, the APIM reply, and the exact callbacks expected (each validated against the gateway contract `ConfirmedHearing`). Cover: enforcement case, hearing-updated with and without an allocation change, a non-enforcement case, a mixed group hearing, the earliest of several sitting days, UK summer and winter time, a Progression failure for one case, case not found, no court centre, no hearing days, APIM failing, and an event with neither key.
- [X] T099 *(2026-09-29: scenario 10 found that a 2xx reply lacking a required field was returned as a 200; now 502 INVALID_RESPONSE, R26.)* Create the scenario runner `../service-cp-crime-results-enforcementgateway/src/test/java/uk/gov/hmcts/cp/integration/HearingResultedScenarioIntegrationTest.java` with folders under `src/test/resources/scenarios/hearing-resulted/`: the request (default `hearingresulted/request.json`), the APIM reply, and the expected HTTP status, APIM call count, forwarded request, and response. Every response is validated against the contract (`HearingResultedResponse` for 200, `ErrorResponse` otherwise). Cover: pass-through success, an empty `nowsDataItems` reply, Libra 400/404/500, a reply that isn't JSON, an empty 2xx, a redirect, an APIM timeout, and invalid requests (missing field, `caseUrn` too long, unknown result code, malformed JSON) that never reach APIM. Document both scenario formats in `../service-cp-crime-results-enforcementgateway/README.md`.

### Code review (2026-09-29; research.md R27)

- [X] T100 Review W, G and A against spec.md, plan.md, tasks.md, research.md and the constitution; fix each confirmed defect test-first (research.md R27) and record the decisions left open as research.md §2 items 20-24.
- [X] T101 Review the integration scenarios against spec.md (US1-US4 acceptance scenarios, edge cases), contracts/, quickstart.md and the CIMD-4246 story. W gains scenarios 13-28: repeat delivery, reshare after a failed first share, TFOUT/WWDN renames with no data request, only some codes needing NOWS items (story scenario 5), a code GOB doesn't list dropped and BPOCRFSD kept, duplicate codes, caseURN too long, no address line 1, organisation defendant, the defendant-level account number, a reply for another caseUrn, an empty data-item reply, gateway 400/502 (downstream timeout, INVALID_RESPONSE)/3xx; the runner takes `events` in order and `logMustContain`, and checks the fixture PII in every scenario. G gains 415 and the semantic pass-through of replies. The story's "empty NOWS array" and "send codes outside the list" are superseded by the gap-analysis decisions (US3 scenario 2, FR-009).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 → T002 → T003/T004. T005-T007 have no dependencies.
- **Foundational (Phase 2)**: depends on T003 (and T004 for G). **Blocks all user stories.**
- **US1 (Phase 3)**: depends on Phase 2. The MVP.
- **US2 (Phase 4)**, **US3 (Phase 5)**, **US4 (Phase 6)**: each depends on US1, because they extend `EnforcementCaseSelector`, `SubmissionStore` and `HearingResultedProcessor`. They are **independent of each other**, but all touch `HearingResultedProcessor.java`, so merge them sequentially or coordinate.
- **US5 (Phase 7)**: depends on US1 and on the BA answers (T059).
- **US6 (Phase 8)**: depends on US1 and US5 (it extends `HearingResultedRequestMapper`), and on the BA/GOB answers (T066).
- **Polish (Phase 9)**: T075-T079 and T087 after US4 (iteration 1). **T085, T088 and T089 before the first deployment beyond local; T090 before production.** T080-T084 and T086 when their triggers occur (T086: tech lead confirmation of R21). T091-T095 (integration test suite) and T096-T099 (the gateway's) any time; later stories extend T094/T099 with scenario folders.

### User Story Dependencies

```text
Setup ─► Foundational ─► US1 (P1, MVP) ─┬─► US2 (P2)
                                         ├─► US3 (P2)
                                         ├─► US4 (P3)
                                         └─► US5 (P3) ─► US6 (P4)
```

### Within Each User Story

- Tests are written first and must fail. Then models/records, then clients/services, then the processor/listener wiring, then the integration test.
- G tasks (T021/T022/T029-T032, T054/T057) can proceed in parallel with W tasks. W integration tests use WireMock for G.

### Parallel Opportunities

- Phase 1: T003, T004, T005, T006, T007.
- Phase 2: T009, T012, T015, T016, T018, T019, T020 in parallel. T008 → T010 → T011 → T014 is sequential.
- US1: all tests T021-T028 in parallel. Then T029, T033, T034 and T037 in parallel. T030 → T031 → T032 (G), and T035 → T036 → T038 → T039 → T040 → T041 (W).
- US2 / US3 / US4 test tasks can be written in parallel by different developers once US1 is merged.
- US6: T067-T072 are six independent mappers in different files, all [P].

---

## Parallel Example: User Story 1

```bash
# Tests first (all different files):
Task: "T021 LibraClientResultHearingTest in ../service-cp-crime-results-enforcementgateway/src/test/java/uk/gov/hmcts/cp/client/"
Task: "T022 HearingResultedControllerTest in ../service-cp-crime-results-enforcementgateway/src/test/java/uk/gov/hmcts/cp/controller/"
Task: "T023 EnforcementCaseSelectorTest in src/test/java/uk/gov/hmcts/cp/service/"
Task: "T024 ReferenceDataClientTest in src/test/java/uk/gov/hmcts/cp/client/"
Task: "T025 ResultCodeResolverTest in src/test/java/uk/gov/hmcts/cp/service/"
Task: "T026 HearingResultedRequestMapperTest in src/test/java/uk/gov/hmcts/cp/mapper/"
Task: "T027 EnforcementGatewayClientTest in src/test/java/uk/gov/hmcts/cp/client/"
Task: "T028 HearingResultedEventListenerTest in src/test/java/uk/gov/hmcts/cp/messaging/"

# Then independent implementation classes:
Task: "T029 LibraCallException (G)"
Task: "T033 SkipReason + EnforcementCaseSelector (W)"
Task: "T034 ResultDefinition + ReferenceDataClient (W)"
Task: "T037 GatewayResult + EnforcementGatewayClient (W)"
```

## Parallel Example: User Story 6

```bash
Task: "T067 EmployerDetailsMapper + test"
Task: "T068 ParentGuardianDetailsMapper + test"
Task: "T069 PaymentTermsMapper + test"
Task: "T070 NextHearingMapper + test"
Task: "T071 TransferLjaMapper + test"
Task: "T072 EnforcerCodeMapper + test"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Setup (the contract published, or in Maven local).
2. Phase 2: Foundational (Postgres, entity, event model, config, ADR).
3. Phase 3: US1.
4. **STOP and VALIDATE**: T041 green, then quickstart scenario 1 against WireMock, and against the APIM mock or GOB simulator once T007 is delivered.
5. Demo to the tech lead and BA.

### Incremental Delivery (matches plan.md §5 iterations)

1. **Iteration 1** = US1 → US2 → US3 → US4. Each is demoable. After US4, every quickstart scenario passes. Keep `payment-due-date-fallback: NONE` in prod config.
2. **Iteration 2** = US5, after the T059 rulings.
3. **Iteration 3** = US6, after the T066 rulings.
4. **Iteration 4+** = Polish T080-T084, triggered by the GOB contract release, product-owner change requests, or agreed retention and encryption.

### Parallel Team Strategy

- Developer A: G tasks (T004, T021/T022, T029-T032, T054/T057, T076, T085).
- Developer B: W foundation + US1 core (T008-T020, T023-T028, T033-T041).
- After US1: split US2, US3 and US4 across developers, and coordinate edits to `HearingResultedProcessor.java`.

---

## Notes

- [P] tasks are in different files with no incomplete dependencies.
- Commit after each task or logical group, in the repo the task touches.
- Never log request or response payloads (FR-017). Log ids, `caseUrn` and outcome only.
- Identifiers (`caseUrn`, `prosecutorDefendantId`) are never truncated or normalised (constitution Principle VI).
- Stop at any checkpoint to validate the story on its own.
