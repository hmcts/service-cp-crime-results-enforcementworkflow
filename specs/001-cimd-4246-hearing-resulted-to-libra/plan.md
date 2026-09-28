# Implementation Plan: CIMD-4246 — Hearing Resulted to GOB (Libra)

**Branch**: `001-cimd-4246-hearing-resulted-to-libra` | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)
**Spec / inputs**:
- [spec.md](spec.md): user stories US1-US6. Iteration 1 = US1-US4, iteration 2 = US5, iteration 3 = US6.
- `specs/001-cimd-4246-hearing-resulted-to-libra/docs/CIMD-4246-gap-analysis.md` (decisions for gaps #1-#16, the authoritative source for the technical decisions)
- `specs/001-cimd-4246-hearing-resulted-to-libra/docs/CIMD-4246.pdf`, `CIMD-4247.pdf` … `CIMD-4256.pdf` (stories)
- `specs/001-cimd-4246-hearing-resulted-to-libra/docs/CCT-1222-Details-20260925.pdf` (Confluence reference)
- `specs/001-cimd-4246-hearing-resulted-to-libra/docs/libra-gateway-hearing-events-openapi-v0.4.0.yml` (Libra contract, with the local gap #2 amendment)

**Artifacts**:
- [research.md](research.md): Phase 0 decisions R1-R21 and open items
- [data-model.md](data-model.md): Phase 1 data model
- [contracts/](contracts/README.md): Phase 1 interfaces
- [quickstart.md](quickstart.md): Phase 1 local run and verification
- [tasks.md](tasks.md): Phase 2 task list (T001-T087), the single source of implementation steps

---

## 1. Summary

When an **Enforcement** case defendant offence is resulted, Common Platform (CP) publishes `public.events.hearing.hearing-resulted`. `service-cp-crime-results-enforcementworkflow` will do the following:

1. Consume the event on its own durable subscription.
2. Filter the event:
   - drop it if `isReshare`;
   - keep only cases with prosecution authority OU code `GAPGD00`;
   - drop out-of-scope shapes.
3. Resolve each judicial result's **shortCode** from reference data, then translate CP codes to GOB codes.
4. Build a Libra `HearingResultedRequest`. `caseUrn` and `prosecutorDefendantId` are passed through as received, and `nowsDataRequest` is included only when at least one NOWS short code matched.
5. Persist the request in Postgres.
6. Call **`service-cp-crime-results-enforcementgateway` `POST /hearingResulted`**. The gateway forwards to **Azure APIM (`cppi-v4` → operation `libra-hearingresulted`)**, APIM forwards to Libra `POST /hearing/result`, and the `HearingResultedResponse` **body** is returned unchanged.
7. Persist the response and status.

Design split (gap #7): **the workflow service holds all business logic. The gateway is a thin outbound connection point.**

Delivery is **iterative**:
- **Iteration 1** is a working end-to-end walking skeleton that produces a schema-valid minimum payload.
- **Later iterations** fill in the payload blocks owned by the sibling stories (CIMD-4247…4256), close the open questions, and absorb Libra contract updates (v0.5.0+) and product-owner change requests.

---

## 2. Technical Context

| Item | Value |
|---|---|
| Language/Version | Java 25 |
| Framework | Spring Boot 4.1.x (`spring-boot-starter-web`, `-artemis`, `-actuator`, `-opentelemetry`); Gradle wrapper |
| Messaging | Artemis native, JMS pub-sub durable subscription on topic `public.event` (existing config in `application.yaml`) |
| New dependencies (workflow) | `spring-boot-starter-data-jpa`, `org.postgresql:postgresql`, `spring-boot-starter-flyway`, `flyway-core`, `flyway-database-postgresql` (the PCR set); `uk.gov.hmcts.cp:api-cp-crime-results-enforcementgateway:<draft version>` (generated models); `spring-boot-starter-validation`; test: `org.wiremock:wiremock-standalone` (as PCR) and **`com.networknt:json-schema-validator`** (contract-schema test against the Libra YAML; chosen over openapi4j, analysis F3) |
| New dependencies (gateway) | `uk.gov.hmcts.cp:api-cp-crime-results-enforcementgateway:<draft version>` (generated `EnforcementHearingApi` + models) |
| Storage | PostgreSQL 18 (`postgres:18-alpine` in docker-compose), Flyway `classpath:db/migration`, `V1.NNN__*.sql` |
| HTTP clients | Spring `RestClient` with named prototype `RestClient.Builder` beans (`referenceDataRestClientBuilder`, `enforcementGatewayRestClientBuilder`) and explicit connect/read timeouts per the research.md R20 budget (the gateway `RestClientConfig` pattern) |
| Testing | JUnit 5, Mockito, AssertJ; `MockRestServiceServer` for client unit tests (gateway pattern); WireMock + real local Postgres for integration tests (PCR `PostgresInitialise` pattern, not Testcontainers); PMD (`gradle pmdTest`) |
| Target platform | AKS (ADO mirror pipeline); listeners active only under the `docker` profile |
| Performance goals | Low volume (enforcement results only). Must not stall the JMS listener thread, so every outbound call has a bounded timeout. |
| Constraints | No change to `cpp-context-*` repos. PII is stored in plain form in iteration 1, with an ADR. No retries or DLQ (CIMD-4259). No reshare handling beyond skipping (the email is a separate story). No system-doc-generator (later story). |
| Scale/Scope | One POST per `(hearingId, caseId, defendantId)` first share |

### Contexts touched

| Context / repo | Change | Owner |
|---|---|---|
| `api-cp-crime-results-enforcementgateway` | Done on branch `dev/CIMD-4246` (committed `180a860`): `POST /hearingResulted` + 31 Libra schemas + tests. Remaining: PR, merge, publish a draft version (T002). | us |
| `service-cp-crime-results-enforcementgateway` | New inbound REST controller `POST /hearingResulted`; new `LibraClient.resultHearing(...)` reading the response body; config; tests. | us |
| `service-cp-crime-results-enforcementworkflow` (this repo) | Everything else: listener, filters, reference-data client, mapping, gateway client, Postgres, ADR, config, tests, docker-compose. | us |
| Azure APIM (`cppi-v4`) | New operation `libra-hearingresulted` (`POST /hearingResulted` → Libra `POST /hearing/result`) + mock policy returning a body. | APIM/platform team (request) |
| Libra/GOB | v0.5.0 contract (`nowsDataRequest` optional; ask for `paymentTerms`/`paymentDueDate` optional). CIMD-4372 simulator. | GOB (dependency) |
| `cpp-context-hearing`, `-staging-prosecutors-civil`, `-prosecution-casefile`, `-progression`, `-reference-data`, `cpp-platform-core-domain` | **No change.** Read-only sources (the event, and `GET /result-definitions/{id}`). | — |

---

## 3. Constitution Check

Evaluated against [constitution v1.0.0](../../.specify/memory/constitution.md), ratified 2026-09-27 from this plan's former gates G1-G8. Re-checked after design.

| Principle | Status | Evidence |
|---|---|---|
| I. Business logic in the workflow, thin gateway | ✅ Pass | Filters, mapping, persistence and idempotency are in W; G only validates, forwards and returns (contracts/gateway-post-hearing-resulted.md) |
| II. Compile-time contracts | ✅ Pass | W and G depend on the `api-cp-crime-results-enforcementgateway` jar with `validateApiSpecVersions` (T003/T004). The Libra schemas are copied verbatim with a sync note; the local `nowsDataRequest` amendment is commented. |
| III. Persistence by convention | ✅ Pass | PCR pattern, `V1.001`, `ddl-auto: validate`, a unique key for idempotency (data-model.md §3) |
| IV. Personal data protection | ✅ Pass | ADR T020 before storing data; the no-PII-in-logs test is in T041 (FR-017) |
| V. Bounded, resilient processing | ✅ Pass | The R20 timeout budget (55s < 60s); outcomes are always recorded; unknown outcomes are never auto-retried (R19/R20) |
| VI. Pass-through identifiers are immutable | ✅ Pass | `caseUrn`/`prosecutorDefendantId` get a length/presence check only, else `MAPPING_FAILED` (R8/R9, T036) |
| VII. Tested and clean by default | ✅ Pass | Test-first tasks in every story; contract-schema test T026; PMD gate T079 |
| VIII. Security boundary | ⚠️ Pass, conditional | FR-018: network isolation in iteration 1 (T085, before the first deployment beyond local); bearer auth later (T086, tech lead). Deferred step and owners stated in R21. |
| IX. Iterative delivery with explicit assumptions | ✅ Pass | Iterations 0-4 (§5); open items with safe interim behaviour and owners (research.md §2); the unsafe test aid (`HEARING_DATE`) is opt-in only (R13) |

No violations, so the Complexity Tracking table (§7) stays empty.

---

## 4. Project Structure

### Documentation (this feature)

```text
specs/001-cimd-4246-hearing-resulted-to-libra/
├── plan.md              # this file: context, gates, structure, iterations, implementation steps
├── research.md          # Phase 0: decisions R1-R21 + open items
├── data-model.md        # Phase 1: event projection, request mapping, DB table, configuration
├── quickstart.md        # Phase 1: local run + verification scenarios
├── contracts/           # Phase 1: how each interface is used (the sources of truth stay in the owning repos)
│   ├── README.md
│   ├── hearing-resulted-event.md
│   ├── reference-data-result-definition.md
│   ├── gateway-post-hearing-resulted.md
│   └── apim-libra-hearingresulted.md
├── tasks.md             # Phase 2: T001-T087 (/speckit-tasks)
├── spec.md              # feature specification (US1-US6, FR-001-FR-018, Clarifications)
├── analysis.md          # /speckit-analyze report + resolution status
├── checklists/requirements.md   # spec quality checklist
├── docs/                # source material: story exports (CIMD-4246…4256), CCT-1222 exports,
│   ├── CIMD-4246-gap-analysis.md                      # decisions (source for this plan)
│   └── libra-gateway-hearing-events-openapi-v0.4.0.yml  # Libra contract (local amendment); used by the contract-schema test
└── adrs/
    └── 001-CIMD-4246-hearing-result-payload-pii-at-rest.md   # T020
```

### Source code: `service-cp-crime-results-enforcementworkflow` (new/changed)

```text
src/main/java/uk/gov/hmcts/cp/
├── client/        ReferenceDataClient, ResultDefinition, EnforcementGatewayClient, GatewayResult
├── config/        RestClientConfig, HearingResultProperties (+ PaymentDueDateFallback)
├── entity/        HearingResultSubmissionEntity, SubmissionStatus
├── event/         HearingResultedEvent
├── mapper/        HearingResultedRequestMapper, MappingResult, MappingFailureReason, Truncate,
│                  DefendantDetailsMapper, EnforcementDetailsMapper                     (US5)
│                  EmployerDetailsMapper, ParentGuardianDetailsMapper, PaymentTermsMapper,
│                  NextHearingMapper, TransferLjaMapper, EnforcerCodeMapper            (US6)
├── messaging/     HearingResultedEventListener (new), PublicEventLoggingListener (unchanged)
├── repository/    HearingResultSubmissionRepository
└── service/       HearingResultedProcessor, EnforcementCaseSelector, SkipReason, StaleSubmissionSweeper,
                   ResultCodeResolver, NowsDataItemSelector, SubmissionStore
src/main/resources/
├── application.yaml                      # + datasource/flyway/jpa/cp.* keys
└── db/migration/V1.001__create_hearing_result_submission.sql
src/test/java/uk/gov/hmcts/cp/...          # unit tests per class; integration/ (IntegrationTestBase, PostgresInitialise, end-to-end)
src/test/resources/events/*.json           # event fixtures (T016)
src/test/resources/expected/*.json         # expected request payloads (T026, T065, T074)
docker/wiremock/mappings/*.json            # optional local stubs (T077)
docker-compose.yml                         # + postgres (+ optional wiremock)
build.gradle                               # + JPA/Postgres/Flyway, API jar, wiremock, schema validator (test)
```

### Source code: `service-cp-crime-results-enforcementgateway` (new/changed)

```text
src/main/java/uk/gov/hmcts/cp/
├── client/        LibraClient (+resultHearing), LibraCallException
├── auth/          EntraTokenValidator, AuthorizationPolicy, EntraAuthProperties, AuthMode   (later, T086)
├── config/        RestClientConfig (+ libra timeouts 5s/40s, R20)
└── controller/    HearingResultedController, GlobalExceptionHandler
docs/InboundAccess.md          # no-ingress + NetworkPolicy for the deploy team (T085)
docs/LibraApimMockPolicy.md    # + libra-hearingresulted request (T007)
build.gradle       # + API jar, spring-boot-starter-validation
```

### Contract: `api-cp-crime-results-enforcementgateway`

```text
src/main/resources/openapi/openapi-spec.yml   # /hearingResulted + Libra schemas (done locally)
src/test/java/uk/gov/hmcts/cp/config/OpenApiObjectsTest.java
```

**Structure decision:** each service keeps its existing single-module Spring Boot layout, and the package names follow the gateway's (`client`, `config`, `event`, `messaging`, `service`), plus PCR-style `entity`/`repository` for persistence.

---

## 5. Iteration Roadmap

| Iteration | Goal | Exit criteria |
|---|---|---|
| **0: Foundations** | Contract published; workflow gets Postgres, Flyway and the ADR; APIM operation requested | Both services compile against the draft API jar. `docker compose up` gives the app + Postgres. Migrations apply. |
| **1: Walking skeleton (CIMD-4246 core)** | End to end: event → filters → shortCode → minimum payload → gateway → APIM (mock or simulator) → persisted response | A GAPGD00 first-share event produces one `SUCCEEDED` row with the response body, against the simulator or mock. Reshare, non-enforcement, out-of-scope and redelivered events produce no POST. |
| **2: Defendant and results completeness** | CIMD-4247 (full individual rules), CIMD-4248 (organisation), CIMD-4253 (`prisonSentenceIndicator`, `jailDays`), CIMD-4255 (result filter finalised), NOWS mapping rows loaded | Story ACs pass. The mapping config is populated from the updated Confluence page. |
| **3: Remaining payload blocks** | CIMD-4249 (employer), CIMD-4250 (parent/guardian), CIMD-4251 (payment terms, `paymentDueDate` ruling), CIMD-4252 (next hearing), CIMD-4254 (`transferLjaCode`), CIMD-4256 (`enforcerCode`) | Story ACs pass, and `payment-due-date-fallback` is removed or set to `NONE` |
| **4+: Contract and hardening** | Libra v0.5.0 sync; bulk reference-data cache; `X-Correlation-ID`; courtroom derivation; retention; PII encryption; deploy onboarding | No open question left in the gap analysis |
| **Separate stories** | CIMD-4259 error handling/replay; the reshare email; the system-doc-generator/NOWS document step | Out of scope for this plan |

---

## 6. Implementation Approach by Iteration

**[tasks.md](tasks.md) is the single source of the step-by-step work** (task IDs, file paths, tests, dependencies). This section only says which task phases make up each iteration and which external dependencies each one needs. *(The earlier step list W0.x/W1.x/G1.x was folded into tasks.md on 2026-09-27; analysis D1.)*

Decision ids (R#) are in [research.md](research.md). Field, table and config detail is in [data-model.md](data-model.md). Interface detail is in [contracts/](contracts/README.md).

| Iteration | tasks.md phases | Tasks | Contexts | Key components |
|---|---|---|---|---|
| 0: Foundations | Phase 1 Setup, Phase 2 Foundational | T001-T020 | A, W, G, X | Contract PR + draft jar; W dependencies, Postgres, Flyway `V1.001`, entity/repository, event projection, fixtures, `RestClientConfig` (R20 budget), `HearingResultProperties`, PII ADR; G API jar dependency; APIM request (T007) |
| 1: Walking skeleton | Phases 3-6 (US1-US4) | T021-T058 | W, G | G: `LibraClient.resultHearing`, `HearingResultedController`, `GlobalExceptionHandler`, Libra timeouts. W: listener, `EnforcementCaseSelector`, `ReferenceDataClient`, `ResultCodeResolver`, `NowsDataItemSelector`, `HearingResultedRequestMapper`, `EnforcementGatewayClient`, `SubmissionStore`, `StaleSubmissionSweeper`, `HearingResultedProcessor` |
| 2: Defendant and results completeness | Phase 7 (US5) | T059-T065 | W | `DefendantDetailsMapper`, `EnforcementDetailsMapper`, result-filter ruling (after the BA rulings, T059) |
| 3: Remaining payload blocks | Phase 8 (US6) | T066-T074 | W | Employer, parent/guardian, payment terms, next hearing, transfer LJA and enforcer-code mappers (after the BA/GOB rulings, T066) |
| 4+: Contract sync and hardening | Phase 9 (Polish) | T075-T087 | A, W, G, X | Docs, local WireMock, quickstart validation, PMD; v0.5.0 sync; bulk reference-data cache; correlation id; retention; `InboundAccess.md` (T085, before the first deployment beyond local); bearer auth (T086); support runbook (T087) |

### External dependencies (not tasks; tracked in research.md §2)

| Needed by | From | Item |
|---|---|---|
| Iteration 1 end-to-end run | APIM/platform team | Operation `libra-hearingresulted` under `cppi-v4`, response body passed through, `forward-request timeout="35"`, mock with a body (T007 raises the request); or the CIMD-4372 GoB simulator |
| Before go-live | Platform team | `NetworkPolicy` / no-ingress for the gateway (T085); prd/prp/prx APIM URLs; the real subscription key; the deploy repo |
| Iteration 2/3 | BA | The US5/US6 rulings (T059, T066); the shortCode → NOWS mapping rows in YAML names (gap #1); the result-filter rule (4246 vs 4255); story wording ("empty array" → "omit" in 4246; CIMD-4251 AC2 and the Example 1 date) |
| Any time | BA → GOB/Libra | v0.5.0: `nowsDataRequest` optional (agreed), `paymentTerms`/`paymentDueDate` optional (asked); the invalid response example; whether repeated submissions are safe (R19) |
| Later iteration | Tech lead | The bearer-auth step of R21 (T086); PII ADR sign-off (T020) |

---

## 7. Complexity Tracking

No gate violations, so nothing to justify.

---

## 8. Constitution Re-check (post-design) and Next Steps

- All constitution principles I-IX pass after design (see §3). VIII is conditional on T085 before the first deployment beyond local. No complexity violations (see §7).
- **Git branch:** `001-cimd-4246-hearing-resulted-to-libra` was created by the speckit `before_specify` hook (`/speckit-git-feature`). Matching feature branches are needed in `api-cp-crime-results-enforcementgateway` (currently `team/CCT-1222`) and `service-cp-crime-results-enforcementgateway`.
- **Next:** re-run `/speckit-analyze`, then `/speckit-implement`, starting with iteration 0 → 1 (tasks.md Phases 1-6).
