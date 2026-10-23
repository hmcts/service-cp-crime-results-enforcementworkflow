# Specification Analysis Report: 002-enforcement-nows-generation

**Date**: 2026-10-09 (run 4) | **Artifacts**: spec.md (US1-US6, FR-001..FR-021, SC-001..SC-009), plan.md, tasks.md (T001-T081), research.md (F1-F18, R1-R21, OQ-1..OQ-14), data-model.md, contracts/ (3), quickstart.md (scenarios 1-23) | **Constitution**: v1.1.1

**History**:
- Run 1 (2026-10-08, T001-T061): 4 HIGH (I1, I2, I3, C1), all resolved.
- Run 2 (2026-10-08, T001-T063): 1 HIGH (I4), 1 MEDIUM (I5), 2 LOW (I6, U4), all resolved.
- Run 3 (2026-10-09, T001-T081): 1 HIGH (U6: H had no share time for the correlation id), 3 MEDIUM (U7, U8, A3), 1 LOW (T1).
- Since run 3 (2026-10-09): answers OQ-10 to OQ-12 (non-offence results once; adjourned offences never sent; nothing new → reuse the most recent GOB data, also for NOWS issued again); **interim correlation attribute = hearing day** (W: event `hearingDay`; H: `nowContent.orderDate`), to be revisited (OQ-9); same-day reshares share the day's id (H versions by `sharedTime`); the "same type of result" check applies to amended NOWs; payload flag `gobDataReused`; BLOCKED marks removed.

## Run-3 findings: status

| ID | Resolution |
|----|------------|
| U6 | **Resolved (interim)**: R18 uses the hearing day; H reads it from the schema-required `nowContent.orderDate`; T035 implements it; nothing blocked. The residual risk is tracked as R1 below. |
| U7 | **Resolved**: answer 4 / OQ-12 (FR-020, T047, scenario 34). |
| U8 | Open, non-blocking (OQ-13, configurable default NEXH/ADJNN); carried as U8 below. |
| A3 | Open, non-blocking, default "a reshare is never sent" recorded as OQ-14; carried as A3 below. |
| T1 | **Resolved**: spec Context reworded. |
| I4 (run 2) | **Still resolved under the new key**: the check now depends only on the NOW (amended → checked against the day's accumulated covered ids), so it holds whichever of the reshare hand-over and the amended NOW arrives first (R17, T070, scenario 22). |

## Findings (run 4)

| ID | Category | Severity | Location(s) | Summary | Recommendation |
|----|----------|----------|-------------|---------|----------------|
| R1 | Risk (accepted, interim) | MEDIUM | spec Assumptions, FR-021; research R18, F17, OQ-9; contract `hearingnows-enforcement-now-handling.md` "Correlation" | H takes the hearing day from `nowContent.orderDate`, the latest ordered date of the defendant's results, entered by hand in the court UI. When it differs from the hearing day, the NOW never matches and fails "no GOB data" after the maximum wait (visible, never wrong data). A related case: a NOW the existing flow issues again on a later hearing day for a defendant with no results ordered that day carries the earlier day's `orderDate`, so it matches the earlier day's kept data (fine while retained) and fails "no GOB data" once that data is released, instead of using the re-handed most recent data (answer 4). | Accepted by the developer as interim. Keep the attribute isolated (`ShareCorrelationId`, `NowShareCorrelationResolver`); settle a guaranteed attribute with the tech lead and architect (OQ-9). Watch "no GOB data" failures in QA (quickstart 23). |
| U8 | Underspecification | MEDIUM | spec FR-018, Assumptions; research R19, F18, OQ-13; tasks T046, T051 | "Adjourned" is recognised by `nextHearing`, `isAdjournmentResult` or a configurable code list (NEXH, ADJNN), because result categories do not mark enforcement final results (F18). GOB's own code list includes ADJNN, which this rule never sends. | BA confirmation (OQ-13). The code list is configuration. |
| A3 | Ambiguity | MEDIUM | spec FR-016, FR-018; research F13, OQ-14 | A second share on the same hearing day is a reshare (per-day flag), so a never-sent offence first given its final result in it is not sent (default: a reshare is never sent to GOB). | Developer/BA decision (OQ-14); the default is safe (nothing double-sent) but can leave an offence unsent. |

No duplication, no unresolved placeholders (`TODO`/`TBD`/`???`: 0 in spec, plan, tasks), no constitution conflicts, no task-order contradictions (the reuse hand-over is built in US6, T053, and reused by US5, T071).

## Coverage Summary

| Requirement | Has task? | Task IDs | Notes |
|---|---|---|---|
| FR-001 hand over after GOB accepts | ✅ | T039, T044 | |
| FR-002 payload incl. hearing day, share time, correlation id | ✅ | T022, T030, T038, T043 | |
| FR-003 record outcome; write-only (own read on reuse) | ✅ | T018, T039, T044, T053, T057, T071 | |
| FR-004 failure never resends to GOB | ✅ | T056-T059 | |
| FR-005 only the workflow; enforcement-specific command | ✅ | T024, T029, T030 | |
| FR-006 hold Enforcement NOWS | ✅ | T025, T026, T028, T034 | |
| FR-007 NOWS decided/built by the existing flow | ✅ | T028 (e) | |
| FR-008 complete waiting / keep early data (per hearing day) | ✅ | T023, T032, T034, T035 | |
| FR-009 data placement + existing chain | ✅ | T012, T027, T036 | |
| FR-010 matching by correlation id; duplicates; same-day replace; race | ✅ | T023, T025, T061-T064 | R1 |
| FR-011 max wait; all data released after retention | ✅ | T065-T067 | |
| FR-012 no hand-over when GOB did not accept | ✅ | T039, T068 | |
| FR-013 existing flow unchanged; GOB updated by W only, once per offence | ✅ | T005, T047-T053 | |
| FR-014 no PII in logs | ✅ | T058, T078 | |
| FR-015 data-protection decision | ✅ | T004, T075 | |
| FR-016 reshare reuse hand-over, never to GOB | ✅ | T069, T071, T073 | A3 |
| FR-017 same-type check on amended NOWs | ✅ | T070, T072, T073 | |
| FR-018 each offence once; final results only; non-offence results once | ✅ | T009, T019, T045, T046, T050-T052 | U8 |
| FR-019 later hearing day processed; repeated delivery no effect | ✅ | T009, T044, T047, T049 | |
| FR-020 nothing new → no GOB call, reuse hand-over | ✅ | T047, T048, T049, T053, T068 | |
| FR-021 hearing-day correlation id, same rule in W and H | ✅ | T010, T011, T020, T021, T023, T033, T035, T044 | R1 (interim) |
| SC-001..SC-007 | ✅ | via the FRs above | |
| SC-008 ≤ 10 minutes | ✅ | T080 (timings) | |
| SC-009 no offence sent twice; later offences sent | ✅ | T047, T049, T080 | |

## Constitution Alignment Issues

None. Idempotency by unique keys: W submission `(correlation_id, case_id, defendant_id)` (a second share of a hearing day is a reshare, which creates no submission), sent-offence partial unique indexes, H kept-data PK with `sharedTime` versioning and status-conditional NOW completion (Principle III). Nothing re-sent to GOB (Principle V). New Flyway migrations only (Principle III). Correlation rule shared through the contract's test vectors (Principle II, v1.1.1). The interim attribute is an explicit, recorded assumption (Principle IX).

## Unmapped Tasks

None. T006 gathers OQ-9, OQ-13 and OQ-14 answers.

## Metrics

- Total requirements: 21 FRs + 9 SCs (30)
- Total tasks: 81 (setup 6, foundational 16, US1 22, US6 11, US2 5, US3 4, US4 4, US5 6, polish 7)
- Coverage: 30/30 have ≥1 task (100%)
- Ambiguity count: 1 (A3)
- Duplication count: 0
- Critical issues: 0
- High issues: 0

## Next Actions

- **No blockers**: no CRITICAL or HIGH findings; `/speckit-implement` can start (MVP: Setup, Foundational, US1, US6).
- Non-blocking, with safe defaults: R1/OQ-9 (guaranteed correlation attribute, tech lead and architect), U8/OQ-13 (BA: adjournment recognition, ADJNN), A3/OQ-14 (same-day reshare with a newly final offence), and the earlier OQ-1, OQ-2 (verify before go-live, T005), OQ-4, OQ-5, OQ-6, OQ-7, OQ-8.
