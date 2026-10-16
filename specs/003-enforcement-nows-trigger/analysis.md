# Specification Analysis Report: feature 003 (Triggering NOWS Generation for Enforcement Hearings)

**Run**: 2026-10-20, `/speckit-analyze` over `spec.md`, `plan.md`, `tasks.md` (and `research.md`, `data-model.md`,
`contracts/`), against constitution **1.2.0**. Read-only for the artefacts; this report is saved as required by the
constitution (Development Workflow & Quality Gates). Resolution status per finding is in the last column.

## Findings

| ID | Category | Severity | Location(s) | Summary | Recommendation | Status |
|----|----------|----------|-------------|---------|----------------|--------|
| C1 | Constitution | CRITICAL | adrs/001-gob-data-on-public-event.md; constitution IV | Principle IV requires every at-rest personal-data decision to be recorded in an ADR **under `specs/001-cimd-4246-hearing-resulted-to-libra/adrs/`**. The broker's persistent store holds GOB data (at rest); the ADR is in `specs/003-…/adrs/`. | Record the ADR in `specs/001-…/adrs/` (e.g. `002-gob-data-on-public-event.md`) and keep a pointer in 003; plan/research/contracts link to it. | Resolved: ADR recorded as `specs/001-…/adrs/002-CIMD-4530-gob-data-on-public-event.md`; 003 `adrs/001-…` is a pointer; links updated (plan, research R18/OP-6, contract) |
| C2 | Constitution | CRITICAL | tasks.md T029, T046; constitution IV | Principle IV: "A test MUST assert [no personal data in logs] for each new flow". W's new flow has it (T028, T022) but H's new event-processor flow (handles GOB data) has no log-assertion test task. | Add an H task: extend `EnforcementNowsLogTest` (or a new test) to assert the processor and re-keyed tasks log ids only. | Resolved: task T031 (H `EnforcementNowsLogTest` for the processor, re-keyed tasks and add-NOW service) |
| H1 | Coverage (security) | HIGH | spec FR-003; plan Constitution VIII; tasks (none) | "Internal network only, no external API gateway" is the security boundary for an endpoint that changes GOB accounts, but no task makes or verifies it (deployment/ingress config, no public route). T074 lists values only. | Add a task: W ingress internal-only in the deployment values (D5) and a check (e.g. documented verification in quickstart); state the owner. | Resolved: task T076 (internal-only ingress requirement + per-environment verification, owner developer/platform) |
| M1 | Coverage | MEDIUM | spec FR-020; plan D4 | FR-020 (data-protection decision before production) has no task; it is only a dependency. | Add a Polish task "ADR accepted, or production switch-on blocked" with the owner. | Resolved: task T077 (production switch-on blocked until the ADR is Accepted); plan Constitution IV and D4 updated |
| M2 | Coverage (NFR) | MEDIUM | spec SC-006 | 10-minute end-to-end target has no task (no timing check). | Add a quickstart/IT step recording timings (task poll, GOB call, H chain) in the dev environment. | Resolved: task T083 (timing measurement against the 10-minute target) |
| M3 | Constitution | MEDIUM | constitution II; tasks T003 | Release builds must use fixed contract versions; T003 adds a draft gateway-contract version, no task switches to the fixed version. | Add a Polish task: fixed `api-cp-crime-results-enforcementgateway` version before release (`validateApiSpecVersions`). | Resolved: task T078 (fixed contract version before release) |
| M4 | Constitution / workflow | MEDIUM | constitution "feature branch per spec"; plan Dev workflow row | Work continues on feature 002's branch `dev/CIMD-3063` (developer decision); plan marks it Pass without justification. | Note it as a justified deviation in plan Complexity Tracking (developer manages branches; 002 not merged). | Resolved: plan Dev workflow row + Complexity Tracking justification |
| M5 | Underspecification | MEDIUM | data-model.md state machine; spec FR-012 | No state for "cannot publish": GOB accepted but no account number, or the selected defendant's master id differs from the notification's. Feature 002 recorded "no account number" as FAILED. | Add a final state (e.g. `PUBLISH_REJECTED` with reason) and a test in US1. | Resolved: `PUBLISH_REJECTED` state (data-model.md, event contract); tests in T027, implementation in T042 |
| M6 | Inconsistency | MEDIUM | spec Assumptions "Feature switch"; research R15 | Spec still says the switch-off meaning "is to be settled in planning"; R15 settled it. | Update the spec assumption to point at R15. | Resolved: spec assumption points to R15 |
| L1 | Inconsistency | LOW | spec FR-011; contract 409 | The 409 for partly known request ids is in the contract and R1, not in the spec. | Add one line to FR-011 or the edge cases. | Resolved: FR-011 rejects partly known request ids |
| L2 | Ambiguity | LOW | tasks T003 | "Testcontainers `redis` or embedded" leaves the choice open. | Pick Testcontainers `redis` in T003 (Docker is already required locally for the Postgres container; embedded Redis libraries are unmaintained). | Resolved: T003 uses Testcontainers `redis` |
| L3 | Coverage | LOW | spec FR-016; tasks | No test asserts that a held enforcement NOW never expires (only the expiry task's removal, T060). | Add an assertion to `EnforcementNowsIT` (T032). | Resolved: T033 asserts a held enforcement NOW never expires |
| L4 | Terminology | LOW | spec "results-processing app" vs plan/tasks "FA"/"Function App" | Defined in both; acceptable. | None. | Accepted |

## Coverage Summary

| Requirement | Has task? | Task IDs | Notes |
|---|---|---|---|
| FR-001 | Yes | T033, T051, T031 | |
| FR-002 | Yes | T034, T053 | |
| FR-002a | Yes | T064, T068 | |
| FR-003 | Partial | T022, T023, T035 | network isolation not tasked (H1) |
| FR-004 | Yes | T022, T035 | |
| FR-005 | Yes | T044 | |
| FR-006 | Yes | T024, T025, T038, T043 | cache off until D3 |
| FR-007 | Yes | T039 | |
| FR-008 | Yes | T054–T057 | |
| FR-009 | Yes | T055, T058 | |
| FR-010 | Yes | T055 | |
| FR-010a | Yes | T056 | interim |
| FR-011 | Yes | T022, T035, T056 | |
| FR-012 | Yes | T026, T028, T040, T041 | M5 |
| FR-013 | Yes | T029–T032, T046–T048 | |
| FR-014 | Yes | T021, T029, T046 | |
| FR-015 | Yes | T061–T067 | |
| FR-016 | Partial | T060 | L3 |
| FR-017 | Yes | T034, T052 | |
| FR-018 | Yes | T031–T033 | |
| FR-019 | Partial | T022, T028, T069 | H log test missing (C2) |
| FR-020 | No | — | M1 |
| SC-001–SC-005, SC-007 | Yes | T028, T032, T056, T063, T069 | |
| SC-006 | No | — | M2 |

**Unmapped tasks**: none (T001 plan artefact, T002–T005 setup, T072–T078 polish are cross-cutting).

## Metrics

- Total requirements: 22 FR + 7 SC = 29
- Total tasks: 78
- Coverage (≥ 1 task): 26 / 29 = 90% (FR-020, SC-006 none; FR-003, FR-016, FR-019 partial)
- Ambiguity count: 1 (L2)
- Duplication count: 0
- Critical issues: 2 (C1, C2); High: 1 (H1)

## Remediation (2026-10-20)

All findings resolved in spec.md, plan.md, research.md, data-model.md, contracts/gob-data-public-event.md and tasks.md
(now 83 tasks; ids renumbered). The coverage table above shows the state before remediation: FR-003, FR-016, FR-019,
FR-020 and SC-006 now have tasks (T076, T033, T031, T077, T083), so coverage is 29 / 29.

## Next actions (before remediation)

- Resolve C1, C2 and H1 before `/speckit-implement` (constitution gate: no CRITICAL or HIGH).
- M1–M6 and L1–L3 are small edits to spec, data-model and tasks; recommended in the same pass.

## Re-run (2026-10-20, after remediation)

Full detection passes (duplication, ambiguity, underspecification, constitution, coverage, inconsistency) over the
remediated artefacts and constitution 1.2.0.

| ID | Category | Severity | Location(s) | Summary | Resolution |
|----|----------|----------|-------------|---------|------------|
| N1 | Constitution | CRITICAL | constitution V (1.2.0 retry bullet); research R10/R12 | The 1.2.0 bullet allowed automatic retry/replay only "while the external system is known not to have received the request", contradicting the previous bullet's idempotent exception; stage 2 republishes on an unknown send outcome, relying on H's idempotency. | Resolved: constitution 1.2.1 (PATCH) aligns the bullet ("or when that system is confirmed idempotent for the request"); R12 records H's idempotency basis (unique `request_id`, conditional status update); plan re-evaluated. |
| N2 | Inconsistency | MEDIUM | research R16 vs data-model.md | R16 listed `PUBLISH_FAILED` and omitted `NO_GOB_DATA`, `SKIPPED_NOT_ENFORCEMENT`, `PUBLISH_REJECTED`. | Resolved: R16 now mirrors the data-model state machine. |

Checks with no finding: all 22 FR and 7 SC have tasks (29 / 29); task ids sequential T001–T083 and every intra-task
reference resolves (T014, T021, T044, T042, T002, parallel and phase ranges); no placeholders or `NEEDS CLARIFICATION`;
no stale references to the removed contract repo, branch `dev/CIMD-4530` or ADR 001; IT per new endpoint (T022) and per
new event entry point (T033); log assertions per new flow (W T022/T028, H T031).

**Result**: 0 CRITICAL, 0 HIGH, 0 MEDIUM, 0 LOW open. Gate for `/speckit-implement` passed.

## Re-run (2026-10-22, after the product decisions)

Review of spec.md, plan.md, research.md, data-model.md, tasks.md and contracts/ after the product decisions of
2026-10-22 (spec.md Clarifications Session 2026-10-22) and T059 (share with no result code: nothing sent to GOB or to
the NOWS service).

| ID | Category | Severity | Location(s) | Summary | Resolution |
|----|----------|----------|-------------|---------|------------|
| P1 | Underspecification | HIGH | spec FR-010, Clarifications 2026-10-22; research R21 | Results whose code is longer than the contract's 10 characters are left out, but no requirement said so. | Resolved: FR-010, the clarification and R21 state the rule (left out and recorded, never truncated). |
| P2 | Inconsistency | HIGH | spec FR-011; contract 409 | A repeat with another reshare indicator was not covered by FR-011, though the API rejects it (409). | Resolved: FR-011 includes the reshare indicator; 409 wording aligned in the contract, its W copy and the API doc. |
| P3 | Coverage | MEDIUM | spec Superseded requirements | Dropping feature 002 FR-020 (no-code share reused earlier GOB data) was not listed. | Resolved: row added (replaced by FR-010); T059 done, README updated. |
| P4 | Inconsistency | MEDIUM | spec SC-002 | SC-002 did not measure "amend & reshare never sent to GOB". | Resolved: SC-002 adds 0 amend & reshares sent to GOB. |
| P5 | Underspecification / coverage | MEDIUM | spec FR-021; tasks | FR-021 sat under the results-processing app heading, did not say that only amended NOWS are regenerated, and had no task. | Resolved: own "NOW distribution" heading, scope sentence added, task T088. |
| P6 | Inconsistency | MEDIUM | plan Storage, Constitution III, Project structure, D7 | Migration range `V1.006`–`V1.013`, key `(notification_id, …)` and "gateway unchanged" did not match data-model.md (I4) and D7. | Resolved: `V1.006`–`V1.009`, key on `correlation_id`, gateway rebuilt on the open `resultCode` (D7, T087). |
| P7 | Inconsistency | LOW | tasks T014, T016, T040 | Still described `notification_id` columns and keys. | Resolved: superseded notes added, as for T010. |
| P8 | Inconsistency | LOW | tasks T057, T046 | Progress of the scenario rework not recorded. | Resolved: progress notes added. |
| P9 | Inconsistency | LOW | tasks Implementation Strategy | "(+ T079 before code)" pointed at the build task; the plan artefact is T001. | Resolved: T001. |
| P10 | Inconsistency | LOW | research R7, R9, OP-15, header; plan Input | Stale `isReshare` and key sentences, OP-15 task ref (T074 → T075), clarification dates, OP ranges. | Resolved. |

### Metrics (2026-10-22)

- Total requirements: 22 FR (FR-010a withdrawn) + 7 SC = 29
- Total tasks: 88 (T001–T088)
- Coverage (≥ 1 task): 29 / 29; FR-010a withdrawn (no task); FR-021 → T088
- Ambiguity count: 0; duplication count: 0

**Result**: 0 CRITICAL, 0 HIGH open. Gate for `/speckit-implement` still passed.

## Re-run (2026-10-23)

Review after the final review fixes of 2026-10-23.

- Total tasks: 91 (T001–T091), 67 done, 24 open.
- Added: T089 (simulator accepts any result code of 1–10 characters), T090 (TEMP diagnostics rollback before main),
  T091 (reshare waits while the first share is in progress).
- First-share-in-progress retry documented: spec FR-009, research R9, data-model.md state machine, README.
- TEMP diagnostics exception recorded: spec FR-019, plan Constitution IV, research I19, checklists, README; removed
  before main (T090).
- Release checklist added to plan.md and referenced from quickstart.md.

**Result**: 0 CRITICAL, 0 HIGH open.
