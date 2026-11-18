# Specification Analysis Report: feature 004 (CIMD-4251)

**Artifacts**: spec.md, plan.md, tasks.md (+ research.md, data-model.md, contracts/, constitution 1.2.1)
**Mode**: read-only analysis; saved here because the constitution ("Development Workflow & Quality Gates") requires the
report as `analysis.md` with a resolution status per finding.

## Run 2 - 2026-11-16 (after remediation): PASS

No CRITICAL or HIGH findings remain. `/speckit-implement` may start once T002 (contract merge) and T003
(implementation-plan artifact) are done.

- Tasks: 40 (renumbered in execution order after the fixes; IDs below are the new ones).
- Coverage: 18/18 requirements (14 FR + 4 SC) have at least one task.
- New findings in run 2: none.

## Findings (run 1, 2026-11-16) and resolution

| ID | Category | Severity | Summary | Resolution |
|----|----------|----------|---------|------------|
| C1 | Constitution (IX) / Inconsistency | CRITICAL | US2 removed the INSTL/LUMSI/PDATE/PTFOR guard before US1 added the mapper, so a payment terms result could be sent without its block and marked SUCCEEDED (silently lost); the old T018 expected `MAPPING_FAILED` that nothing produced. | **Resolved**: T016 keeps a guard (`PAYMENT_TERMS_UNREADABLE`, "not mapped yet", test first); T019 asserts it; T024 replaces it with `PaymentTermsMapper`; plan "Delivery order" step 2 and tasks "Implementation Strategy" 1 updated. |
| M1 | Coverage gap | MEDIUM | No test that a payment terms result left out by `OffenceResultFilter` gives no block. | **Resolved**: T026, scenario `50-payment-terms-already-sent-not-repeated`. |
| M2 | Quality gate (CLAUDE.md) | MEDIUM | Implementation-plan HTML artifact before coding not tracked. | **Resolved**: T003 (Phase 1, before any code). |
| M3 | Constitution (IV) / FR-014 | MEDIUM | TEMP DIAGNOSTICS would log payment amounts and dates in deployed environments; tests strip it. | **Resolved (tracked)**: T039 release blocker, plan "Delivery order" step 8. |
| L1 | Underspecification | LOW | Log assertion values could match unrelated text. | **Resolved**: T021 uses distinctive synthetic values and adds them to `NEVER_LOGGED`. |
| L2 | Coverage | LOW | DID prompt ignored - untested. | **Resolved**: T020 case with `test-did`. |
| L3 | Inconsistency | LOW | Plan said "~3 new main classes"; tasks create 4. | **Resolved**: plan says 4. |
| L4 | Coverage | LOW | STE testing (SC-003) is manual. | **Accepted**: quickstart §4; QA hand-over. |

## Coverage Summary (run 2, new task IDs)

| Requirement | Has task? | Task IDs |
|---|---|---|
| FR-001 block only with a payment terms result | Yes | T020, T023, T024 |
| FR-002 omit block; stand-in removed | Yes | T013-T017 (guard T016) |
| FR-003 INSTL fields | Yes | T020-T023 |
| FR-004 LUMSI adds lump sum | Yes | T020, T022, T023 |
| FR-005 PDATE / PTFOR | Yes | T027-T029 |
| FR-006 W/F/M mapping | Yes | T009, T010, T020 |
| FR-007 payment card flag | Yes | T030-T032 |
| FR-008 parent-to-pay flag | Yes | T030-T032 |
| FR-009 mandatory fields | Yes | T020, T023 |
| FR-010 amounts and dates | Yes | T009, T010 |
| FR-011 unsendable reasons | Yes | T016, T019, T020, T022, T023, T025 |
| FR-012 no enforcement override | Yes | T020, T023 |
| FR-013 rest of request unchanged | Yes (strict JSON match) | T014, T022, T026 |
| FR-014 no values logged | Yes | T009, T020, T021, T039, T040 |
| SC-001 complete results sent with block | Yes | T022, T028, T031 |
| SC-002 no stand-in date | Yes | T014-T017 |
| SC-003 all CIMD-4251 scenarios pass | Yes (STE part manual) | T020, T022, T027, T030 |
| SC-004 incomplete → unsendable with reason | Yes | T022, T025 |

**Constitution alignment**: all principles pass. I (logic in workflow; gateway version-only, T033-T034), II
(generated model, commented amendment, fixed version T037), III (no schema change), IV (no values logged; T039, T040),
V (no new call; recorded outcome), VI (identifiers untouched), VII (tests first; every scenario POST validated by
`GatewayContract`; PMD), VIII (no endpoint change), IX (safe interim: guard T016, empty OQ-1 configuration → unsendable; OQ-1 resolved 2026-11-18 from CP reference data).

**Unmapped tasks**: none (T001-T004, T033-T040 are setup, gateway and polish tasks serving all stories).

## Metrics (run 2)

- Total requirements: 18 | Total tasks: 40 | Coverage: 100%
- Ambiguity: 0 | Duplication: 0 | Critical: 0 | High: 0 | Medium: 0 open | Low: 0 open (1 accepted)
