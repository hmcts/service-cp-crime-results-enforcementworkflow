# Specification Quality Checklist: Enforcement NOWS Generation with GOB Account Data

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-06 | **Revised**: 2026-10-07
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Iteration 1 (2026-10-06): 2 [NEEDS CLARIFICATION] markers (behaviour when GOB did not accept; who creates the enforcement NOW), presented as Q1-Q2.
- Iteration 2 (2026-10-06): Q1 answered (A: the NOW waits and fails with "no GOB data"); applied.
- Iteration 3 (2026-10-06): Q2 answered (B: the hand-over creates the NOWS); spec rewritten without a waiting step.
- Iteration 4 (2026-10-07): **revised after the tech lead review.** Two flows run in parallel from the same hearing result (the enforcement workflow → GOB, and Common Platform's existing NOWS flow → NOWS service), in either order. Q2's answer is superseded: the existing NOWS flow creates the NOWS (FR-007), the hand-over completes them, and the NOWS service handles both arrival orders (FR-006, FR-008, FR-010), with a maximum wait and a retention period (FR-011). Q1's answer applies as written (FR-012). FR-013 (GOB updated once; the existing NOWS flow must not send a second update) added, with its dependency on the PaaS NOWS functions recorded in Assumptions. All items pass.
- Iteration 5 (2026-10-07): the existing NOWS flow (PaaS NOWS functions) is not changed (developer decision, confirmed in its code: it sends its own GOB imposition only for financial-imposition results, which enforcement hearing results are not). FR-013 reworded to "existing NOWS flow unchanged; GOB updated once by the enforcement flow"; SC-004 adjusted; how the NOWS service recognises an Enforcement case's NOW (from the case's prosecuting authority) and the CIMD-4462 data model added to Assumptions/Dependencies. All items still pass.
- Iteration 6 (2026-10-08): amend & reshare. The question was sent to the BA/BPO; the expected answer ("use the first share's GOB data") is recorded as an explicit assumption (Clarifications, Assumptions) with US5 and FR-016, so tasks can proceed; FR-003 notes the workflow's own read of its stored reply for this case. All items pass.
- Iteration 7 (2026-10-08): BA and BPO answers received; developer decision: amend & reshare is never sent to GOB. US5 / FR-016 rewritten and FR-017 added (same type of result → most recent GOB data; completely different result → not generated, "result not sent to GOB"); emails recorded as the notification story's; SC-007 reworded (analysis I3); rollout rule added (analysis A1). All items pass.
- Iteration 8 (2026-10-09): later shares of the same hearing and the share correlation id (tech lead; developer decision "each offence sent to GOB once"). US6 (P1), FR-018 to FR-021 and SC-009 added; FR-002/005/008/010/013/016 and the entities adjusted; FR-011 reworded (all kept data released, analysis I5); failure reasons added to the Enforcement NOW states (analysis U4). The NOWS service's source of the share time is not yet agreed: recorded as an assumption pointing to research OQ-9 (a design question, not a scope question, so no [NEEDS CLARIFICATION] marker). The correlation id is named as an agreed identifier rule, not an implementation detail. All items pass.
- Iteration 9 (2026-10-09): developer answers (non-offence results once; adjourned offences never sent, final results only; nothing new → reuse the most recent GOB data, also for NOWS issued again) and the interim correlation attribute (hearing day, to be revisited because the NOW's order date is entered in the court UI) applied to FR-002, FR-010, FR-016 to FR-018, FR-020, FR-021, edge cases and assumptions. All items pass.
- Domain system names (GOB, the NOWS service, the existing NOWS flow, the document generator, CIMD-4462) are named as business systems, as in feature 001's spec; no frameworks or code-level APIs are specified.
- Items marked incomplete require spec updates before `/speckit.clarify` or `/speckit.plan`.
