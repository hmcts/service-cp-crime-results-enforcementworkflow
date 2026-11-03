<!--
Sync Impact Report
- Version change: 1.2.0 → 1.2.1 (PATCH, 2026-10-20: Principle V retry bullet aligned with the unknown-outcome bullet:
  automatic retry/replay is also allowed when the receiver is confirmed idempotent; found by /speckit-analyze, finding N1)
- Previous change: 1.1.1 → 1.2.0 (MINOR: materially expanded guidance; no principle removed or redefined)
- Modified principles (1.2.0, 2026-10-19, feature 003 / CIMD-4530):
  I. Business Logic in the Workflow, Thin Gateway: "event filtering" generalised to "trigger handling"; added the
     results-processing Function App's role (creates NOW requests and request ids, notifies the workflow; no
     enforcement decision beyond the shared Enforcement-case rule)
  V. Bounded, Resilient Processing: "listener" generalised to "listener or task executor"; added the automatic retry
     and replay rule for work queued by the workflow (never for an unknown external outcome)
  VII. Tested and Clean by Default: Function App tests use its own tooling (Jest)
- Modified sections: Architecture & Technology Constraints:
  - inbound triggers: internal REST notification (from the Function App) instead of the hearing-resulted public event;
    remaining Artemis listeners still under the `docker` profile
  - outbound CP public events: the workflow MAY publish to Artemis with the CPP envelope (`_metadata`) and `CPPNAME`
  - task execution: `cp-task-manager`, with the workflow owning the library's DDL in its own Flyway migrations
  - `cpp-context-azure-legalaidagency` (results-processing Function App) MAY be changed for enforcement NOWS
    triggering (feature 003), under the same rules as `cpp-context-hearing-nows`
  - template packages: `controller` added
- Added sections: none
- Removed sections: none
- Templates:
  ✅ .specify/templates/plan-template.md: "Constitution Check" is generic; no change needed
  ✅ .specify/templates/spec-template.md: no mandatory sections added or removed; no change needed
  ✅ .specify/templates/tasks-template.md: no new task type; no change needed
  ⚠ .specify/templates/commands/*.md: directory not present in this project; nothing to check
  ✅ specs/003-enforcement-nows-trigger/plan.md: Constitution Check re-evaluated against 1.2.0
  ✅ specs/001-…/plan.md, specs/002-…/plan.md: evaluated against earlier versions; their listener-based design is
     superseded by feature 003 (no edit needed)
- Deferred TODOs: none
- Previous: 1.1.0 → 1.1.1 (2026-10-08, Principle II clarification for `cpp-context-hearing-nows`);
  1.0.0 → 1.1.0 (hearing NOWS context may be changed, feature 002); template → 1.0.0 on 2026-09-27
-->

# service-cp-crime-results-enforcementworkflow Constitution

## Core Principles

### I. Business Logic in the Workflow, Thin Gateway

- All enforcement decision logic MUST live in `service-cp-crime-results-enforcementworkflow`: trigger handling, eligibility, mapping, code translation, persistence and idempotency.
- `service-cp-crime-results-enforcementgateway` MUST remain a thin outbound connection point. It validates against the contract, forwards, and returns the upstream status and body unchanged. It holds no business rules and no persistence.
- CP-internal reads (e.g. reference data) belong to the workflow. Only calls to external enforcement systems (GOB/Libra via APIM) go through the gateway.
- The hearing NOWS context (`cpp-context-hearing-nows`) owns NOWS document assembly and generation: it combines the GOB account data handed to it with hearing and case data, and drives the document generator, material storage and distribution. It MUST NOT re-decide enforcement eligibility or GOB outcomes; those stay in the workflow.
- The results-processing Function App (`cpp-context-azure-legalaidagency`) creates NOW requests as today and, for an Enforcement case's defendant, gives them request ids and notifies the workflow. It MUST NOT make any other enforcement decision; the Enforcement-case rule (prosecuting authority code) it applies MUST be the workflow's rule.

*Rationale*: one owner for enforcement behaviour (strangler-fig consolidation), and a gateway that can be reasoned about and secured on its own.

### II. Compile-Time Contracts

- Every service-to-service interface this project owns MUST be defined in an OpenAPI contract repo (e.g. `api-cp-crime-results-enforcementgateway`) and consumed through its published, generated API jar. Hand-written copies of contract DTOs are not allowed for new interfaces.
- Exception for `cpp-context-hearing-nows` (see Architecture & Technology Constraints): for commands and events this project adds to that context, the contract is the context's own RAML resource and JSON schema, not an OpenAPI repo. A consumer in this project MUST keep a verbatim copy of that schema marked "keep in sync; do not edit independently" and MUST validate every payload it builds against the copy in a test. A hand-written request model in the consumer is allowed only together with that validation.
- Release builds MUST depend only on fixed contract versions (`X.Y.Z`), enforced by `validateApiSpecVersions`. Draft versions are allowed during development only.
- Schemas copied from external contracts (e.g. the Libra Gateway API) MUST be copied verbatim and marked "keep in sync; do not edit independently". Local amendments MUST be commented and tracked until the owner publishes them.

*Rationale*: contract drift then shows up as a build failure, not a production 400.

### III. Persistence by Convention

- Persistence in this project's services (workflow, gateway) MUST follow the `service-cp-crime-results-pcr` pattern: Spring Data JPA, PostgreSQL, Flyway at `classpath:db/migration`, and `V1.NNN__description.sql` migrations with one change per file.
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
- A failure while processing one message or task MUST NOT stop the listener or task executor or block later work. Every attempt MUST end in a recorded final outcome.
- Work the workflow queues (e.g. a notification) MAY be retried automatically with back-off, and replayed from a stored failure, only while the external system is known not to have received the request, or when that system is confirmed idempotent for the request (e.g. it ignores a repeated request id), consistent with the previous bullet; replays MUST never repeat a step whose outcome is already recorded.
- When the outcome at an external system is unknown (timeout, interruption), the attempt MUST be recorded as such and MUST NOT be retried automatically, unless the external system is confirmed idempotent.

*Rationale*: JMS listener threads must never stall, and live external accounts must never be double-updated.

### VI. Pass-Through Identifiers Are Immutable

- Identifiers received from or issued by an external system (e.g. `caseUrn`, `prosecutorDefendantId`/GoB account number) MUST be passed through exactly as received: no trimming, case change, prefix handling, reformatting or truncation.
- Length and presence are the only validations. A violation makes the submission unsendable (recorded with a reason), never silently corrected.
- Truncation to target field limits is allowed only for descriptive fields (names, addresses) where the story says so.

*Rationale*: these values are correlation keys; altering them breaks reconciliation with GOB.

### VII. Tested and Clean by Default

- Every new component MUST have unit tests, and every flow MUST have an integration test: real local Postgres (PCR `PostgresInitialise` pattern), and WireMock or `MockRestServiceServer` for HTTP. In `cpp-context-hearing-nows`, the same obligation is met with that context's own tooling (Maven unit tests, `*IT` integration tests with WireMock stubs via `runIntegrationTests.sh`).
- Tests are written first and MUST fail before the implementation is added. Test tasks are mandatory in `tasks.md`, not optional.
- Payloads built for external contracts MUST be validated against the contract schema in a test.
- `./gradlew build`, including PMD (`pmdMain`, `pmdTest`), MUST pass with no violations before merge. Suppressions need an inline justification.

- In `cpp-context-azure-legalaidagency`, the same obligation is met with its own tooling (Jest unit tests per changed module; requests to the workflow validated against the workflow's OpenAPI contract).

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

- Java 25, Spring Boot 4.x and the Gradle wrapper, following `service-hmcts-crime-springboot-template` conventions (packages `controller`, `client`, `config`, `event`, `messaging`, `service`, `mapper`, `entity`, `repository`).
- The workflow is triggered by an internal REST notification from the results-processing Function App (feature 003); it no longer consumes the hearing-resulted public event. Any CP event it still consumes is read from Artemis `public.event` through a dedicated durable subscription with a `CPPNAME` selector, active only under the `docker` profile.
- The workflow MAY publish CP public events to Artemis: a persistent JMS text message whose body is the CPP envelope (`_metadata` with at least `id`, `name`, `createdAt`) plus the payload, with the `CPPNAME` property set to the event name. The topic name MUST be configurable. The consuming context owns the event's JSON schema (Principle II exception).
- Queued work, retries and replays use `cp-task-manager` (`uk.gov.hmcts.cp`). Its tables are created by the workflow's own Flyway migrations (copied verbatim, "keep in sync"), with the library's schema management switched off.
- Outbound HTTP uses Spring `RestClient` with named, prototype-scoped builders per target, each with its own timeouts.
- `cpp-context-*` repositories are read-only sources (events, query APIs) for this project, with two exceptions: `cpp-context-hearing-nows` MAY be changed for enforcement NOWS generation (feature 002), and `cpp-context-azure-legalaidagency` (results-processing Function App) MAY be changed for enforcement NOWS triggering (feature 003). Changes there MUST:
  - follow that repository's own conventions, not this project's Spring Boot/Gradle ones (for `cpp-context-hearing-nows`: CPP framework on WildFly/Java EE, Maven, RAML and JSON schemas, Drools access rules, Liquibase viewstore changesets, jobstore tasks; for the Function App: Azure Durable Functions in JavaScript, its feature flags and its tests), with their own build, unit and integration tests;
  - keep their existing behaviour for every non-enforcement NOW and share unchanged;
  - go through that repository's owning team for review and merge (CODEOWNERS);
  - still satisfy Principles II, IV, V, VI, VII and VIII where they apply (contracts, personal data, bounded processing, immutable identifiers, tests, security boundary).
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

**Version**: 1.2.1 | **Ratified**: 2026-09-27 | **Last Amended**: 2026-10-20
