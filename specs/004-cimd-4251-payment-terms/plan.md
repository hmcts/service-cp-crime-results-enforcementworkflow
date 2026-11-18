# Implementation Plan: Payment terms in the hearing result sent to GOB (CIMD-4251)

**Branch**: `004-cimd-4251-payment-terms` (directory; work stays on the user's branch, see spec header) | **Date**: 2026-11-15 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/004-cimd-4251-payment-terms/spec.md`

## Summary

Send GOB the payment arrangement the court made in an enforcement hearing, and nothing about payment terms when it
made none. The workflow reads the INSTL / LUMSI / PDATE / PTFOR result's prompts and builds the contract's
`paymentTerms` block per CIMD-4251's mapping table; with no such result the block is omitted, which the gateway
contract now allows (LOCAL AMENDMENT CIMD-4251, as Libra v0.6.0). The stand-in due date and its
`payment-due-date-fallback` setting are removed. Missing, unreadable or conflicting values make the share unsendable
with a specific reason. Prompt references and value formats are configuration; their `application.yaml` defaults
come from CP reference data and the hearing context (OQ-1, resolved 2026-11-18, research R3).

Supersedes feature 001 tasks T066 (`paymentDueDate` ruling only) and T069 (PaymentTermsMapper).

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.x, Gradle wrapper (workflow and gateway); OpenAPI contract repo built with Gradle + OpenAPI Generator 7.x
**Primary Dependencies**: generated contract jar `uk.gov.hmcts.cp:api-cp-crime-results-enforcementgateway` (draft from the CIMD-4251 merge), Lombok, Jackson
**Storage**: PostgreSQL via Spring Data JPA/Flyway - **no schema change** (failure reasons go in existing `error_detail` text)
**Testing**: JUnit 5, AssertJ, Spring Boot tests with local Postgres and WireMock (`ScenarioIntegrationTest`), contract validation via `GatewayContract`; PMD
**Target Platform**: AKS (STE first), Linux containers
**Project Type**: web services (workflow + thin gateway) and an OpenAPI contract repo
**Performance Goals**: no change; the block is built in memory from the already-loaded event (no extra calls)
**Constraints**: no invented values (constitution IX); no amounts, dates or prompt values in logs or `error_detail` (constitution IV, FR-014); gateway stays logic-free (constitution I)
**Scale/Scope**: one optional block per submission; 4 payment terms result codes; 4 new main classes, ~6 changed; ~8 new scenario folders; 7 existing expected requests updated (6 scenario `expected-request.json` + `expected/hearing-resulted-request-minimum.json`)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design (below).*

| Principle | Assessment | Status |
|---|---|---|
| I. Business logic in the workflow, thin gateway | All mapping in the workflow (`PaymentTermsMapper`); the gateway only takes the new contract version | PASS |
| II. Compile-time contracts | Block built with the generated `PaymentTerms` model; contract change is a commented LOCAL AMENDMENT matching Libra v0.6.0; draft version for development, fixed `X.Y.Z` before release (tracked in tasks) | PASS |
| III. Persistence by convention | No schema change; failure reasons stored as today | PASS (N/A) |
| IV. Personal data protection | No new personal data stored; amounts/dates never logged; scenario tests assert their absence from logs | PASS |
| V. Bounded, resilient processing | No new outbound call; mapping failure is a recorded final outcome before any send; no retry | PASS |
| VI. Pass-through identifiers immutable | Identifiers untouched; amounts are not identifiers and are never truncated or rounded (R6) | PASS |
| VII. Tested and clean | Tests first for mapper, properties, resolver, scenarios, gateway IT; every request validated against the contract; PMD clean | PASS |
| VIII. Security boundary | No endpoint added or exposed; existing auth unchanged | PASS (N/A) |
| IX. Iterative delivery, explicit assumptions | OQ-1/2/3 recorded with safe interim behaviour (unsendable / omit) and owners (research "Open items"); OQ-1 handled by configuration, resolved from CP reference data, not guesses | PASS |
| Workflow & gates | Speckit sequence followed; `/speckit-analyze` before implement; implementation-plan HTML artifact before coding (CLAUDE.md) | PASS |

No violations; Complexity Tracking not needed.

**Post-design re-check (after Phase 1)**: data-model.md and contracts/ add no table, endpoint, outbound call or logged
value; configuration defaults are the safe option. All principles still PASS.

## Project Structure

### Documentation (this feature)

```text
specs/004-cimd-4251-payment-terms/
├── spec.md
├── plan.md              # this file
├── research.md          # R1-R9, open items
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── payment-terms-block.md
│   └── payment-terms-configuration.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code

```text
api-cp-crime-results-enforcementgateway/              (sibling repo, contract)
└── src/main/resources/openapi/openapi-spec.yml       # DONE 2026-11-15: paymentTerms optional

service-cp-crime-results-enforcementworkflow/         (this repo)
├── build.gradle                                      # contract version bump
├── docker-compose.yml, README.md                     # PAYMENT_DUE_DATE_FALLBACK removed; payment terms documented
├── src/main/resources/application.yaml               # payment-terms block (OQ-1 values); fallback removed
├── src/main/java/uk/gov/hmcts/cp/
│   ├── config/PaymentTermsProperties.java            # NEW  cp.hearing-result.payment-terms, validated
│   ├── config/HearingResultStartupChecks.java        # fallback warning → empty payment-terms keys warning (R9)
│   ├── config/HearingResultProperties.java           # paymentDueDateFallback removed
│   ├── config/PaymentDueDateFallback.java            # DELETED
│   ├── service/ResolvedResult.java                   # + prompts
│   ├── service/ResultCodeResolver.java               # fills prompts
│   ├── mapper/PaymentTermsMapper.java                # NEW  results → PaymentTermsOutcome (R4-R7)
│   ├── mapper/PaymentTermsOutcome.java               # NEW  None | Block | Unsendable
│   ├── mapper/PaymentValueParser.java                # NEW  amounts, dates, frequency, yes/no (R6)
│   ├── mapper/MappingFailureReason.java              # + 3 PAYMENT_TERMS_*, - PAYMENT_DUE_DATE_UNAVAILABLE
│   └── mapper/HearingResultedRequestMapper.java      # uses PaymentTermsMapper; stand-in block removed
└── src/test/
    ├── java/uk/gov/hmcts/cp/mapper/PaymentTermsMapperTest.java, PaymentValueParserTest.java   # NEW
    ├── java/uk/gov/hmcts/cp/config/PaymentTermsPropertiesTest.java                             # NEW
    ├── java/…/mapper/HearingResultedRequestMapperTest.java, service/ResultCodeResolverTest.java,
    │   config/HearingResultPropertiesTest.java                                                  # updated
    ├── java/…/integration/PaymentDueDateNoneIntegrationTest.java                                # DELETED (behaviour gone)
    ├── java/…/integration/WorkflowStubsIntegrationTestBase.java                                 # fallback property → test payment-terms config
    ├── resources/events/hearing-resulted-payment-terms-*.json                                   # NEW fixtures (synthetic)
    └── resources/scenarios/49..56-payment-terms-*/                                                  # NEW scenarios; the 6 existing expected-request.json + expected/hearing-resulted-request-minimum.json: paymentTerms removed

service-cp-crime-results-enforcementgateway/          (sibling repo)
├── build.gradle                                      # contract version bump
└── src/test/java/uk/gov/hmcts/cp/integration/HearingResultedGatewayIntegrationTest.java  # + request without paymentTerms forwarded
```

**Structure Decision**: existing single-service layout (template packages `config`, `service`, `mapper`); no new
module. The simulator needs no change (it already validates against Libra v0.6.0). `cpp-aks-deploy` needs no change
(no environment-specific values; `PAYMENT_DUE_DATE_FALLBACK` is not set in any values file).

## Delivery order (iterations, constitution IX)

1. **Contract**: merge the amendment; note the published draft version.
2. **US2 (MVP, P1)**: version bump; remove the stand-in block and the fallback setting; requests without a payment
   terms result omit the block; existing scenarios updated. A share with a payment terms result stays unsendable
   (`PAYMENT_TERMS_UNREADABLE`, temporary guard) until US1, so it is never sent without its block (constitution IX).
   Independently shippable: removes the stand-in date risk.
3. **Foundation**: `ResolvedResult.prompts`, `PaymentTermsProperties`, `PaymentValueParser`, startup warning.
4. **US1 (P1)**: INSTL and LUMSI blocks, incomplete/unreadable/conflicting handling.
5. **US3 (P2)**: PDATE and PTFOR.
6. **US4 (P3)**: payment card and parent-to-pay flags.
7. **Gateway**: version bump + IT.
8. **Polish**: README, 001 tasks marked superseded, full builds, PII-in-logs assertions; TEMP DIAGNOSTICS rollback
   before `main` (it would log payment amounts and dates).

Steps 3-6 are tested with fake references (scenario tests) and with the real `application.yaml` values
(`PaymentTermsApplicationConfigIntegrationTest`, OQ-1 resolved 2026-11-18); STE confirms them against real shares.

## Risks

| Risk | Mitigation |
|---|---|
| OQ-1 values differ in shape from the configuration model (e.g. one prompt carrying several values) | Configuration is per logical value with lists; if CP's shape differs, only `PaymentTermsMapper`'s reading step changes, not the block rules |
| Removing the block from every existing expected request hides an unintended change | Diff limited to the `paymentTerms` key; `GatewayContract` validation on every scenario |
| Old `PAYMENT_DUE_DATE_UNAVAILABLE` rows in STE | Left as they are (text); not re-sent, per existing first-share rule |
| Release with a draft contract version | `validateApiSpecVersions` fails the release build; fixed `X.Y.Z` tracked as a task |

## Complexity Tracking

Not needed: no constitution violations.
