<!--
Sync Impact Report
- Version change: (unratified template) → 1.0.0
- Principles defined (from the plan gates G1-G8 in specs/001-cimd-4246-hearing-resulted-to-libra/plan.md §3):
  I. Business Logic in the Workflow, Thin Gateway (G1)
  II. Compile-Time Contracts (G2)
  III. Persistence by Convention (G3)
  IV. Personal Data Protection (G4)
  V. Bounded, Resilient Processing (G5)
  VI. Pass-Through Identifiers Are Immutable (G6)
  VII. Tested and Clean by Default (G7)
  VIII. Security Boundary (G8)
  IX. Iterative Delivery with Explicit Assumptions (new)
- Added sections: Architecture & Technology Constraints; Development Workflow & Quality Gates; Governance
- Removed sections: none (template placeholders replaced)
- Templates:
  ✅ .specify/templates/plan-template.md: "Constitution Check" is generic (gates derived from this file); no change needed
  ✅ .specify/templates/spec-template.md: no mandatory sections added or removed; no change needed
  ✅ .specify/templates/tasks-template.md: "Tests are OPTIONAL" note amended to reference Principle VII
  ⚠ .specify/templates/commands/*.md: directory not present in this project; nothing to check
  ✅ specs/001-cimd-4246-hearing-resulted-to-libra/plan.md §3: now references this constitution (v1.0.0)
- Deferred TODOs: none
-->

# service-cp-crime-results-enforcementworkflow Constitution

## Core Principles

### I. Business Logic in the Workflow, Thin Gateway

- All enforcement decision logic MUST live in `service-cp-crime-results-enforcementworkflow`: event filtering, eligibility, mapping, code translation, persistence and idempotency.
- `service-cp-crime-results-enforcementgateway` MUST remain a thin outbound connection point. It validates against the contract, forwards, and returns the upstream status and body unchanged. It holds no business rules and no persistence.
- CP-internal reads (e.g. reference data) belong to the workflow. Only calls to external enforcement systems (GOB/Libra via APIM) go through the gateway.

*Rationale*: one owner for enforcement behaviour (strangler-fig consolidation), and a gateway that can be reasoned about and secured on its own.

### II. Compile-Time Contracts

- Every service-to-service interface this project owns MUST be defined in an OpenAPI contract repo (e.g. `api-cp-crime-results-enforcementgateway`) and consumed through its published, generated API jar. Hand-written copies of contract DTOs are not allowed for new interfaces.
- Release builds MUST depend only on fixed contract versions (`X.Y.Z`), enforced by `validateApiSpecVersions`. Draft versions are allowed during development only.
- Schemas copied from external contracts (e.g. the Libra Gateway API) MUST be copied verbatim and marked "keep in sync; do not edit independently". Local amendments MUST be commented and tracked until the owner publishes them.

*Rationale*: contract drift then shows up as a build failure, not a production 400.

### III. Persistence by Convention

- Persistence MUST follow the `service-cp-crime-results-pcr` pattern: Spring Data JPA, PostgreSQL, Flyway at `classpath:db/migration`, and `V1.NNN__description.sql` migrations with one change per file.
- The datasource is configured from env vars with local defaults, and Hibernate runs with `ddl-auto: validate`. Applied migrations MUST NOT be edited; change the schema only through new migrations.
- Idempotency MUST be enforced by database constraints (unique keys), not only by application checks.

*Rationale*: a consistent operational model across the `cp-crime-results` service family.

### IV. Personal Data Protection

- Every decision on storing personal data at rest (plain vs encrypted, retention) MUST be recorded in an ADR under `specs/001-cimd-4246-hearing-resulted-to-libra/adrs/` before the data is stored. It MUST NOT be assumed from a sibling service.
- Defendant personal data (names, DOB, NINO, addresses, contact and bank details) MUST NOT be written to application logs. Logs carry identifiers, `caseUrn` and outcomes only. A test MUST assert this for each new flow that handles such data.

*Rationale*: payloads exchanged with GOB carry high-sensitivity PII, including bank details.

### V. Bounded, Resilient Processing

- Every outbound call MUST have explicit connect and read timeouts.
- Across a call chain, each hop's total timeout (connect + read) MUST be strictly shorter than its caller's read timeout, so outcomes are reported back rather than lost. End-to-end budgets MUST meet the feature's success criteria.
- A failure while processing one message MUST NOT stop the listener or block later messages. Every attempt MUST end in a recorded final outcome.
- When the outcome at an external system is unknown (timeout, interruption), the attempt MUST be recorded as such and MUST NOT be retried automatically, unless the external system is confirmed idempotent.

*Rationale*: JMS listener threads must never stall, and live external accounts must never be double-updated.

### VI. Pass-Through Identifiers Are Immutable

- Identifiers received from or issued by an external system (e.g. `caseUrn`, `prosecutorDefendantId`/GoB account number) MUST be passed through exactly as received: no trimming, case change, prefix handling, reformatting or truncation.
- Length and presence are the only validations. A violation makes the submission unsendable (recorded with a reason), never silently corrected.
- Truncation to target field limits is allowed only for descriptive fields (names, addresses) where the story says so.

*Rationale*: these values are correlation keys; altering them breaks reconciliation with GOB.

### VII. Tested and Clean by Default

- Every new component MUST have unit tests, and every flow MUST have an integration test: real local Postgres (PCR `PostgresInitialise` pattern), and WireMock or `MockRestServiceServer` for HTTP.
- Tests are written first and MUST fail before the implementation is added. Test tasks are mandatory in `tasks.md`, not optional.
- Payloads built for external contracts MUST be validated against the contract schema in a test.
- `./gradlew build`, including PMD (`pmdMain`, `pmdTest`), MUST pass with no violations before merge. Suppressions need an inline justification.

*Rationale*: behaviour is safe to iterate on while the external contracts are still changing.

### VIII. Security Boundary

- Any endpoint whose effect changes an external account (e.g. the gateway's `POST /hearingResulted`) MUST accept requests only from explicitly authorised callers, and MUST NOT be reachable from outside the platform's internal network.
- The enforcement mechanism (network isolation, service-to-service bearer auth) MUST be stated in the feature spec and plan, with owners for any deferred step.
- Secrets (e.g. APIM subscription keys) come only from environment or secret stores, never from committed config.

*Rationale*: an unauthenticated internal endpoint that alters live GOB accounts is a critical risk.

### IX. Iterative Delivery with Explicit Assumptions

- Features are delivered in iterations. Each iteration MUST run end to end and be independently testable (e.g. the MVP user story first).
- An open business or contract question MUST NOT block an iteration silently. It is recorded as an explicit assumption with safe interim behaviour (in `research.md` and the spec's Assumptions or Clarifications), with a named owner, and revisited when answered.
- Interim behaviour MUST default to the safe option (e.g. do not send rather than send invented data). Less safe test aids MUST be switched on explicitly.

*Rationale*: the GOB contract and sibling stories are still moving; progress must not depend on hidden guesses.

## Architecture & Technology Constraints

- Java 25, Spring Boot 4.x and the Gradle wrapper, following `service-hmcts-crime-springboot-template` conventions (packages `client`, `config`, `event`, `messaging`, `service`, `mapper`, `entity`, `repository`).
- Inbound CP events are consumed from Artemis `public.event` through dedicated durable subscriptions with a `CPPNAME` selector. Listeners are active only under the `docker` profile.
- Outbound HTTP uses Spring `RestClient` with named, prototype-scoped builders per target, each with its own timeouts.
- No changes to `cpp-context-*` repositories are made from this project. They are read-only sources (events, query APIs).
- Every outbound call to GOB/Libra goes gateway → Azure APIM (`cppi-v4`) → Libra. APIM owns the onward OAuth2 to Libra.

## Development Workflow & Quality Gates

- The Spec Kit workflow is mandatory for features: `/speckit-specify` → (`/speckit-clarify`) → `/speckit-plan` → `/speckit-tasks` → `/speckit-analyze` → `/speckit-implement`. Speckit structure and conventions take precedence over ad-hoc document layouts.
- `/speckit-analyze` MUST report no CRITICAL or HIGH findings before `/speckit-implement` starts. Its report is saved as `analysis.md` in the feature directory, with a resolution status per finding.
- Decisions and their rationale are recorded in the feature's `research.md` (decision / rationale / alternatives). Cross-story business decisions are also tracked in the relevant `docs/<date>/…gap-analysis.md`.
- Work happens on a feature branch per spec. Commits and PRs are made only when the developer asks.

## Governance

- This constitution overrides conflicting practices in this repository. The plan's "Constitution Check" MUST evaluate every principle. Violations are either fixed or justified in the plan's Complexity Tracking table.
- **Amendments** are made with `/speckit-constitution`, recorded in the Sync Impact Report at the top of this file, and propagated to the dependent templates and active feature artifacts in the same change.
- **Versioning** (semantic): MAJOR for removing or redefining a principle incompatibly; MINOR for a new principle or section, or materially expanded guidance; PATCH for wording or clarification.
- **Compliance review**: every PR review checks the principles that apply. `/speckit-analyze` treats a conflict with any MUST in this file as CRITICAL.

**Version**: 1.0.0 | **Ratified**: 2026-09-27 | **Last Amended**: 2026-09-27
