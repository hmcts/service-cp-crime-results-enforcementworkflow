# Feature Specification: Hearing Resulted to GOB (Libra) for Enforcement Cases

**Feature Branch**: `001-cimd-4246-hearing-resulted-to-libra`
**Created**: 2026-09-25
**Status**: Draft
**Input**: User description: "CIMD-4246: When an Enforcement case (prosecutionAuthorityOUCode GAPGD00) defendant offence is resulted in Common Platform (public.events.hearing.hearing-resulted), send a HearingResultedRequest to GOB (Libra) via service-cp-crime-results-enforcementgateway and Azure APIM, requesting the NOWS data items matching the resulted short codes, and persist the returned HearingResultedResponse for later NOWS generation. Reshares never call GOB; out-of-scope shapes (multi-defendant, multi-case, linked application) are ignored. Decisions: specs/001-cimd-4246-hearing-resulted-to-libra/docs/CIMD-4246-gap-analysis.md; stories CIMD-4246..4256."

**Source material**:
- Jira stories `specs/001-cimd-4246-hearing-resulted-to-libra/docs/CIMD-4246.pdf` … `CIMD-4256.pdf`
- Confluence `specs/001-cimd-4246-hearing-resulted-to-libra/docs/CCT-1222-Details-20260925.pdf`
- GOB contract `specs/001-cimd-4246-hearing-resulted-to-libra/docs/libra-gateway-hearing-events-openapi-v0.4.0.yml`
- Decision log `specs/001-cimd-4246-hearing-resulted-to-libra/docs/CIMD-4246-gap-analysis.md`

## Clarifications

### Session 2026-09-27

- Q: What is sent as the payment due date when the results contain no payment terms result? → A: By default nothing is sent. The submission is recorded as unsendable (reason: payment due date unavailable). Using the hearing date as a stand-in is allowed only when explicitly switched on for local, test or simulator environments, never by default. The final rule is to be agreed with the BA, who also needs to confirm where the date in CIMD-4251 Example 1 comes from (p. 4) and whether AC2 (p. 2) should change now that the payment block is mandatory in the GOB contract.
- Q: If a submission was interrupted (for example by a service restart) and it is unknown whether GOB processed it, is it sent again? → A: No, not automatically. It is recorded as failed with the reason "interrupted – outcome at GOB unknown", for follow-up and later replay (CIMD-4259). **This is still an open point:** the BA will confirm with the GOB team whether a repeated submission for the same case and results is safe. If it is, automatic resending can be reconsidered.
- Q: How long may one submission take, end to end, and what happens if the service gives up waiting? → A: At most 60 seconds end to end (SC-006). Each step in the chain gives up before the step that called it, so the real outcome is normally reported back. The per-step limits are recorded in research.md R20. If the workflow's own wait expires, the attempt is recorded as failed with "TIMEOUT – outcome at GOB unknown" and is not resent, the same as an interrupted submission.
- Q: Who may call the enforcement gateway's hearing-result endpoint, and how is that enforced? → A: Only the enforcement workflow. In iteration 1 this is enforced by keeping the endpoint reachable only from the enforcement workflow inside the platform network. Token-based service-to-service authentication follows in a later iteration, subject to tech lead confirmation. The mechanism is recorded in research.md R21.

## User Scenarios & Testing *(mandatory)*

**Actors**:
- The **court user** who results a hearing in Common Platform (CP).
- The **GOB (Libra) enforcement system**, which holds the defendant's enforcement account.
- The **NOWS generation process** (a later story), which needs the account data GOB returns.
- The **support/operations team**, who need to see what was sent and what came back.

### User Story 1 - Resulted enforcement hearing is sent to GOB and GOB's reply is kept (Priority: P1)

A court user results an Enforcement case defendant's offence in CP and shares the results. Without anyone re-keying data, GOB receives the hearing outcome for that defendant's account: the case reference, hearing date, court, defendant, result codes, and the minimum payment and enforcement details. GOB's reply is kept, linked to that hearing, case and defendant.

**Why this priority**: This is the core of CIMD-4246. Without it GOB accounts are not updated after enforcement hearings, and there's no GOB data to produce notices from. Every other story builds on it.

**Independent Test**: Share results for a single-defendant Enforcement case that has at least one GOB-recognised result. Confirm that exactly one submission reaches GOB (or the GOB simulator) carrying the case reference and GOB account number exactly as CP holds them, and that GOB's reply is stored and retrievable against that hearing, case and defendant.

**Acceptance Scenarios**:

1. **Given** a single-defendant Enforcement case, **When** its hearing results are shared for the first time, **Then** one hearing-result submission is sent to GOB for that defendant, and GOB's reply is stored against that hearing, case and defendant.
2. **Given** the case reference is held in CP as received from GOB, **When** it is sent, **Then** it is sent unchanged, and the reference in GOB's reply is checked against it.
3. **Given** the defendant's GOB account number was supplied when the case was created, **When** the submission is sent, **Then** that exact account number is sent as the defendant's reference. It is never derived from the case-level related reference.
4. **Given** a CP result code that GOB knows under a different name (TFOUT, WC, WWDN), **When** it is sent, **Then** GOB receives its own code (TFOOUT, DW, WDN respectively).
5. **Given** a case prosecuted by any authority other than Enforcement, **When** its results are shared, **Then** nothing is sent to GOB.

---

### User Story 2 - Only first-time, in-scope results are sent (Priority: P2)

GOB can't handle amended or reshared results, or case shapes that are out of scope for this phase. So CP sends each defendant's first share once, and ignores reshares, duplicates of the same share, and out-of-scope shapes. Nothing is lost silently: each skip is traceable.

**Why this priority**: Without it, GOB could receive duplicate or unsupported updates to live accounts. It protects the data integrity of User Story 1.

**Independent Test**: Share, reshare and re-deliver results for an Enforcement case, and share results for multi-defendant, multi-case and linked-application hearings. Confirm that only the first share of the single-defendant case produces a submission, and that each other case leaves a traceable reason.

**Acceptance Scenarios**:

1. **Given** results were already shared and sent, **When** the same results are reshared (amend and reshare), **Then** nothing is sent to GOB. The notification for reshares is a separate feature.
2. **Given** a submission already exists for a hearing, case and defendant, **When** the same share is delivered again, **Then** no second submission is sent.
3. **Given** a hearing with more than one Enforcement case, or an Enforcement case with more than one defendant, or an Enforcement case with a linked application, **When** results are shared, **Then** nothing is sent, and the skip and its reason are recorded in the service logs.

---

### User Story 3 - GOB returns the notice data needed for the results given (Priority: P2)

For the result short codes given at the hearing, CP asks GOB to return only the account data items those notices need, for example account balance, account number, warrant number or bank details. The request uses GOB's twelve named data items. When none of the results needs notice data, CP doesn't ask for any.

**Why this priority**: The NOWS generation process can't produce enforcement notices without GOB-held account data. It's independent of User Story 2 and builds on User Story 1.

**Independent Test**: With a short-code-to-data-item mapping configured, share results with two codes needing overlapping data items. Confirm GOB is asked for each item exactly once. Then share results whose codes need no items, and confirm no data request is included.

**Acceptance Scenarios**:

1. **Given** result short codes that each need one or more GOB data items, **When** the submission is sent, **Then** it asks for the union of those items, each item once, using GOB's item names.
2. **Given** result short codes that need no GOB data items, **When** the submission is sent, **Then** it contains no data request at all, rather than an empty one.
3. **Given** GOB returns an empty set of data items, **When** the reply is stored, **Then** it is stored successfully as an empty result.

---

### User Story 4 - Failed or unsendable submissions are visible for follow-up (Priority: P3)

Full error handling and replay come in a later story (CIMD-4259). Until then, support staff can still see which defendants' results were not accepted by GOB, or could not be sent at all, and why, so nothing disappears unnoticed.

**Why this priority**: It limits the risk of lost updates until CIMD-4259 is delivered, but the happy path gives value without it.

**Independent Test**: Force GOB to reject a submission, and share results for a case missing its GOB account number. Confirm each is recorded with a status and reason, and that no retry is attempted.

**Acceptance Scenarios**:

1. **Given** GOB rejects the submission, or doesn't answer in time, **When** the attempt ends, **Then** the submission is recorded as failed with GOB's status and reason, and processing of later results continues.
2. **Given** required data is missing or can't be sent unchanged (no GOB account number, a case reference longer than GOB accepts, no address line 1), **When** results are shared, **Then** nothing is sent, and the submission is recorded as unsendable with the reason.
3. **Given** none of the defendant's results is a code GOB accepts, **When** results are shared, **Then** nothing is sent, and this is recorded with the dropped codes.

---

### User Story 5 - Complete defendant and result details (Priority: P3)

GOB receives the full defendant details, for both individuals and organisations, and the result details that drive prison-sentence handling and jail days. Codes are filtered to those GOB uses, according to the rules agreed in the sibling stories.

**Why this priority**: It refines the minimum submission from User Story 1 into the version agreed in CIMD-4247, 4248, 4253 and 4255. It depends on outstanding BA decisions.

**Independent Test**: Share results for an individual defendant and for an organisation defendant, with CW/SC results carrying jail-day prompts. Confirm the defendant details, the prison-sentence indicator and the jail days match the agreed story examples.

**Acceptance Scenarios**:

1. **Given** an individual defendant, **When** results are shared, **Then** name, date of birth, NI number, address, postcode and telephone numbers are sent within GOB's field limits (CIMD-4247).
2. **Given** an organisation defendant, **When** results are shared, **Then** the organisation name and its address and telephone are sent, with no personal fields (CIMD-4248).
3. **Given** a CW or SC result, **When** results are shared, **Then** the prison-sentence indicator and jail days follow the CIMD-4253 rule. Otherwise the indicator is "N".

---

### User Story 6 - Remaining payment, employer, guardian and enforcement details (Priority: P4)

GOB receives the other optional blocks when the results call for them:
- employer details (CIMD-4249);
- parent/guardian details (CIMD-4250);
- payment terms from payment results (CIMD-4251);
- next hearing and account notes (CIMD-4252);
- transfer court for TFOOUT (CIMD-4254);
- enforcer for warrant results (CIMD-4256).

**Why this priority**: These are separately owned sibling stories, most still in analysis. They extend the submission without changing its flow.

**Independent Test**: For each sibling story, share results matching its example scenario and confirm the corresponding block matches the story's expected payload.

**Acceptance Scenarios**:

1. **Given** an attachment-of-earnings result with employer details captured, **When** results are shared, **Then** employer details are sent. Without employer details, the employer block is left out.
2. **Given** payment-terms results (instalments, lump sum, pay by date), **When** results are shared, **Then** the payment terms match CIMD-4251.
3. **Given** a warrant result, **When** results are shared, **Then** the enforcer code required by GOB is sent (CIMD-4256, subject to reference data CIMD-4336).

---

### Edge Cases

- **Reshare**: never sent (US2), including the case where the first share failed.
- **Same share delivered twice**, for example after a service restart: sent at most once (US2).
- **Codes CP uses but GOB doesn't list** (e.g. PGPAY, LATG): dropped and logged. If no codes remain, nothing is sent (US4).
- **`BPOCRFSD` (BPOC refused)**: a genuine, distinct code, sent as-is.
- **Case reference longer than 36 characters, or GOB account number missing**: not sent and not truncated (US4).
- **Defendant names or addresses longer than GOB allows**: shortened to GOB's limits. Identifiers are never shortened.
- **GOB reply's case reference differs from the one sent**: the reply is stored and the submission stays succeeded; the mismatch is logged as a warning for support to investigate.
- **Service interrupted mid-submission** (e.g. restart during the GOB call): the attempt is recorded as failed with an unknown outcome within the period set in FR-013, and it is **not** resent automatically (US4). Whether a resend is safe is an open point with the BA and GOB.
- **GOB slow or unavailable**: the attempt ends within 60 seconds and is recorded as failed. When the timeout was downstream (APIM or the gateway), GOB's or the gateway's status is recorded. When CP's own wait expired, it is recorded as "TIMEOUT – outcome at GOB unknown" and not resent. Later results are still processed (US4).
- **No payment result, so no payment due date**: see Assumptions (payment due date).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST react to every CP hearing-results share without manual action.
- **FR-002**: The system MUST treat a case as Enforcement only when its prosecuting authority code equals the configured Enforcement authority code (default `GAPGD00`), and MUST NOT send anything for other cases.
- **FR-003**: The system MUST NOT send anything to GOB for a reshare of results.
- **FR-004**: The system MUST ignore, and record the reason for, hearings with more than one Enforcement case, Enforcement cases with more than one defendant, and Enforcement cases with a linked application.
- **FR-005**: The system MUST send at most one submission per hearing, case and defendant.
- **FR-006**: The system MUST send the case reference exactly as held in CP (maximum 36 characters) and MUST NOT reformat or truncate it.
- **FR-007**: The system MUST send the defendant's GOB account number exactly as supplied when the case was created (maximum 36 characters), and MUST NOT derive it from the case-level related reference.
- **FR-008**: The system MUST identify each result's short code from CP reference data, and translate CP codes to GOB codes (TFOUT→TFOOUT, WC→DW, WWDN→WDN, others unchanged).
- **FR-009**: The system MUST send one result entry per distinct GOB-recognised code, and MUST drop and record codes GOB doesn't recognise.
- **FR-010**: The system MUST ask GOB for the de-duplicated union of data items needed by the results' short codes, using GOB's twelve item names, and MUST leave out the data request entirely when no item is needed.
- **FR-011**: The short-code-to-data-item mapping MUST be maintainable without changing the system's behaviour code.
- **FR-012**: The system MUST store each submission sent and GOB's reply against the hearing, case and defendant, so they are available to the later NOWS generation step.
- **FR-013**: The system MUST record the outcome of every attempted submission: accepted, rejected or failed (with GOB's status and reason), unsendable (with the reason), interrupted with the outcome at GOB unknown, or no GOB-recognised result codes. No attempt may remain without a final outcome for longer than a configured period (by default about 6 minutes: 5 minutes before an attempt counts as stale, plus up to 1 minute until the next check).
- **FR-014**: The system MUST end each GOB call within 60 seconds end to end. Every hop's time limit MUST be shorter than its caller's, so the outcome is normally reported back rather than lost. The system MUST continue processing later results after any single failure.
- **FR-015**: The system MUST fill GOB's mandatory fields in every submission, using the agreed defaults (payment card requested "N", parent to pay "N", prison-sentence indicator "N" unless a rule gives "Y").
- **FR-016**: All calls to GOB MUST go through the enforcement gateway, which is the single outbound connection point for enforcement integrations. The enforcement workflow holds all decision logic.
- **FR-017**: The system MUST NOT write defendant personal data to application logs.
- **FR-018**: The enforcement gateway's hearing-result submission path MUST accept requests only from the enforcement workflow. It MUST NOT be reachable from outside the platform's internal network.

### Key Entities

- **Hearing result share**: CP's notification that a hearing's results were shared. It carries the hearing, its cases, defendants, offences and results, whether it is a reshare, and when it was shared.
- **Enforcement case**: a prosecution case whose prosecuting authority is Enforcement. It carries the case reference issued by GOB.
- **Defendant (GOB account holder)**: the defendant on the Enforcement case. It carries the GOB account number, personal or organisation details, and address.
- **Result short code**: the code identifying a result, in CP's vocabulary and translated to GOB's.
- **NOWS data item**: one of GOB's twelve named account data sets (e.g. Account Balance, Account Number, Account Warrant Number, CT Account Bank Details).
- **Submission outcome**: how the outcomes in FR-013 are recorded. Accepted → *succeeded*. Rejected by GOB, gateway or APIM failure, timed out or interrupted (outcome unknown) → *failed*, with GOB's or the gateway's status where known and a reason. Unsendable → *mapping failed*, with the reason. No GOB-recognised result codes → *skipped, no result code*, with the dropped codes.
- **Hearing-result submission**: one attempt to send a defendant's first-share results to GOB. It holds what was sent, GOB's reply, the outcome status and reason, and the hearing, case and defendant it belongs to.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of first-time result shares for in-scope Enforcement defendants with at least one GOB-recognised result are sent to GOB without manual re-keying.
- **SC-002**: 0 submissions are sent for reshares, non-Enforcement cases, out-of-scope case shapes, or repeated deliveries of the same share.
- **SC-003**: 100% of submissions carry the case reference and GOB account number identical to CP's values: 0 altered or truncated identifiers.
- **SC-004**: 100% of attempted submissions have a recorded outcome. Support staff can find the outcome and reason for any defendant's submission within 5 minutes of being asked.
- **SC-005**: GOB's reply is available to the NOWS generation step for 100% of accepted submissions.
- **SC-006**: No submission waits more than 60 seconds for GOB before its outcome is recorded.
- **SC-007**: A mapping change (short code → data items) takes effect without a code change.

## Assumptions

- **Scope of this feature**: CIMD-4246 is the core flow (US1-US4). US5-US6 cover the sibling stories CIMD-4247…4256, delivered in later iterations as their open questions close.
- **Delivery is iterative**: later iterations absorb GOB contract updates (v0.5.0+) and product-owner change requests.
- **GOB contract**: v0.4.0 with the agreed change that the data request is optional. GOB is formalising this in v0.5.0.
- **Payment due date**: GOB currently requires it on every submission. With no payment result there is no source for it, and a pending BA/GOB ruling will decide it (the preferred outcome is that GOB makes it optional). Until then, the default everywhere is to treat such a submission as unsendable. The hearing date may be used as a stand-in only where it is explicitly switched on (local, test, simulator), and it is never on by default.
- **Data item names**: GOB's twelve item names are authoritative. The Confluence page will be updated to match and will supply the short-code-to-item mapping rows. Until then the mapping is empty, so no data request is sent.
- **Result-code filtering**: only codes GOB lists are sent. The final rule is pending the CIMD-4246 vs CIMD-4255 decision.
- **Enforcer code**: warrant results may be rejected by GOB until the enforcer code (CIMD-4256 / CIMD-4336) is delivered.
- **The BA has confirmed** that multi-defendant, multi-case and linked-application cases won't be sent for Enforcement. The guard (FR-004) is defensive only.
- **Error handling**: retries and replay are out of scope (CIMD-4259).
- **A failed first share is final for now**: because reshares are never sent and there are no retries until CIMD-4259, a defendant whose first submission failed stays un-updated at GOB until it is replayed manually or through CIMD-4259. The BA accepts this interim risk.
- **Support access**: in iteration 1, support staff find outcomes with a documented read-only query against the service's submission store (see the runbook in the service README). A support-facing view is not in scope.
- **No production sending yet (code review 2026-09-28):** until payment terms are mapped (CIMD-4251) or `paymentDueDate` becomes optional, the safe default makes every submission unsendable. So the flow is not switched on in production before then (research.md open item 12, tasks.md T090).
- **Single instance:** iteration 1 runs one instance of the workflow service. More than one would clash on the JMS subscription (research.md open item 13, T088).
- **Gateway access control**: iteration 1 meets FR-018 through network isolation, which is deploy configuration. Token-based service authentication is a later iteration, pending the tech lead's decision.
- **Interrupted submissions**: not resent automatically, because GOB's behaviour on a repeated submission is unknown. **Open point:** the BA is to confirm with the GOB team whether repeats are safe (idempotent).
- **Also out of scope**: the reshare email notification, and generating the notice documents (system document generator).
- **Personal data at rest**: stored unencrypted in the first iteration, recorded in an architecture decision. Encryption comes in a later iteration.
- **Dependencies**:
  - CP reference data (result short codes).
  - The enforcement gateway's new hearing-result endpoint.
  - A new APIM operation under the existing enforcement API.
  - The GOB simulator (CIMD-4372) for testing.
