# Feature Specification: Enforcement NOWS Generation with GOB Account Data

**Feature Branch**: not created (the developer manages branches; suggested `dev/CIMD-4462-enforcement-nows`)
**Created**: 2026-10-06 | **Revised**: 2026-10-07 (parallel-flow design after the tech lead review; existing NOWS flow unchanged); 2026-10-08 (amend & reshare decided after the BA/BPO answers: FR-016, FR-017; SC-007 reworded); 2026-10-09 (later shares of the same hearing, each offence sent to GOB once, share correlation id: US6, FR-018 to FR-021, SC-009; interim correlation by hearing day)
**Status**: Draft
**Input**: User description: "Enforcement NOWS generation with GOB data (CCT-1222, FR13; data model CIMD-4462): after service-cp-crime-results-enforcementworkflow receives GOB/Libra's HearingResultedResponse for an enforcement hearing result (feature 001), it synchronously hands the GOB account data (nowsDataItems, account number) to cpp-context-hearing-nows, following hearing-nows' existing pause-and-trigger pattern (a NOW document request waits, the trigger adds the new data, then hearing-nows queries and attaches the other necessary data — hearing-resulted and case details — and calls system-doc-generator to generate the NOWS PDF, then the existing material upload and notification steps). The GOB data is carried in the NOW payload as financialOrderDetails.enfNowsDataItems (schema identical to the gateway contract NowsDataItems), per the reference PR hmcts/cpp-context-hearing-nows#186 (not to be merged). The workflow's submission table becomes write-only (an audit/error-handling record, not read by the NOWS step). Templates on system-doc-generator are designed by another developer." Revised 2026-10-07: "When a hearing is resulted, two flows run in parallel: (1) the enforcement workflow (public hearing-resulted event → GOB/Libra, iteration 1), and (2) Common Platform's existing NOWS process (the PaaS NOWS functions in cpp-context-azure-legalaidagency), which decides whether NOWS are needed and, if so, sends the NOW requests to hearing-nows. Neither is guaranteed to reach hearing-nows first. If the NOW request arrives first, hearing-nows must pause it until the workflow hands over GOB's response; if the workflow's hand-over arrives first, there is no waiting NOW yet and its data must be kept until the NOW request arrives."

## Context

Feature 001 sends an Enforcement case's hearing results to GOB (Libra) (each offence's final result once, US6) and receives GOB's account data synchronously (the "NOWS data items": account number, balance, terms, offences and impositions, warrant details, and so on). This feature uses that data in the court's Notices, Orders, Warrants and Summons (NOWS) for the defendant.

When a hearing is resulted, **two independent flows start from the same event and run in parallel**:

1. **Enforcement flow** (this project): the enforcement workflow sends the result to GOB and receives GOB's account data (feature 001), then hands it to the NOWS service.
2. **Existing NOWS flow** (Common Platform): the NOWS decision process decides, from the hearing results and reference data, whether NOWS are needed and which ones, builds each NOW (defendant, case, results, court) and sends it to the NOWS service, which generates, stores and distributes the documents.

For an Enforcement case, a NOW cannot be generated until it has GOB's data, and the two flows can reach the NOWS service in **either order**. The NOWS service therefore brings them together: a NOW that arrives first **waits** for GOB's data; GOB's data that arrives first is **kept** until the NOW arrives. When both are present, the NOW is completed with GOB's data and generated as usual. The NOWS service already has a similar "wait for GOB" step for financial orders (it waits for GOB's account number); this feature adds the enforcement equivalent, in both directions.

## Clarifications

### Session 2026-10-06

- **Decision (product owner, tech lead, architect)**: GOB's reply is handed to the NOWS service synchronously, right after it is received. Feature 001's submission record becomes write-only: an audit and future error-handling record that the NOWS step does not read.
- **Decision**: The NOWS service is the existing hearing NOWS context. The GOB data block in the NOW document follows the data model of reference change CIMD-4462 (which mirrors GOB's contract exactly). Document templates are designed separately by another developer.
- Q1: What happens to the enforcement NOWS when GOB did not accept the result? → A: It is not generated without GOB's data. Its NOW waits and, after the maximum wait, is recorded as failed with the reason "no GOB data", visible to support; a later error-handling feature (CIMD-4259) can complete it (FR-012).
- Q2: Who creates the enforcement NOW request that uses GOB's data? → Answered "the hand-over itself" on 2026-10-06, **superseded on 2026-10-07** (below).

### Session 2026-10-07 (tech lead review)

- **Decision**: The hand-over does **not** create the NOWS. Common Platform's existing NOWS flow decides whether NOWS are needed and creates them (it already selects NOWS from the hearing results and reference data, and builds their content). The enforcement workflow's hand-over **completes** them with GOB's data. The two flows run in parallel from the same hearing result, so the NOWS service must handle both arrival orders (FR-006 to FR-010). This supersedes Q2's answer.
- **Consequence**: Q1's answer applies as written: when GOB did not accept the result, nothing is handed over and the waiting NOW fails after the maximum wait with "no GOB data".
- **Decision**: Common Platform's existing NOWS flow (the PaaS NOWS functions) is **not changed**. It keeps deciding, from its definitions (mostly reference data), whether a NOWS document is needed and sends the NOW to the NOWS service; the enforcement workflow keeps processing only Enforcement cases among the hearing-resulted events; the two flows meet at the NOWS service, which completes the NOW and sends it to the document generator. The NOWS service's data model for GOB's data is the reference change CIMD-4462 (hearing NOWS PR #186, not merged).
- **Decision (2026-10-08, developer, after the BA and BPO answers)**: an amend & reshare is **never** sent to GOB, in any case (no second request to GOB). When an amend & reshare needs an enforcement NOWS: if the NOWS is for a **result of the same type as one already sent to GOB**, it is generated with the most recent GOB data received for that hearing and defendant (BA/BPO option b); if it is for a **completely different result**, the NOWS cannot be generated because GOB never provided the data it needs, so it is not generated and is visible to support (option c; the email to the enforcement or confiscation team is a separate notification story). FR-016, FR-017.
- **BA/BPO notes recorded (2026-10-08)**: emails to the Enforcement or Confiscation team are chosen by the MET ID prefix of the case's related reference number (equal to CP's Division Code); some result codes also need an email on the first share. Both belong to the notification story, not this feature.
### Session 2026-10-09 (developer, tech lead)

- **Decision (tech lead)**: the enforcement workflow calls a **new NOWS-service operation dedicated to enforcement**, right after GOB's reply (confirms the design of FR-001 and FR-005; unchanged).
- **Decision (tech lead)**: hand-overs and NOWS are correlated by a **share correlation id**: a UUID generated by the same rule in both the enforcement workflow and the NOWS service from the hearing id and a second attribute both hold. The enforcement workflow also uses it to recognise a repeated delivery (FR-010, FR-019, FR-021). The tech lead proposed the share time, which the NOWS service does not have (finding below).
- **Decision (developer)**: **each offence's results are sent to GOB at most once**, across all shares of the case, whether or not a later hearing-resulted payload repeats them. A later share of the same hearing (same hearing id, a later hearing day) that results an offence not yet sent **is** sent to GOB, with that offence's results only. Example: share 1 results offence 1 and adjourns offence 2 → GOB is called for offence 1; share 2 (the adjourned hearing) results offence 2 → GOB is called again, for offence 2 only. This applies to any number and combination of offences (FR-018 to FR-020).
- **Finding (verified in code)**: Common Platform now sets the "reshare" flag **per hearing day** (the share of a new hearing day is a first share, not a reshare); the older hearing-level variant is marked as replaced.
- **Finding (verified in code)**: the NOW that the existing NOWS flow sends to the NOWS service does **not** carry the share time, and the NOWS service receives no hearing event of its own. The only per-share attribute both sides hold is the **hearing day** (the enforcement workflow: the event's hearing day; the NOWS service: the NOW's order date, the latest ordered date of the defendant's results for that hearing day; research F17).
- **Interim decision (developer, 2026-10-09, third round)**: the share correlation id is generated from the **hearing id and the hearing day**, to unblock implementation. It is **to be revisited** (research OQ-9): the order date comes from ordered dates entered by hand in the court UI, so it is not guaranteed to equal the hearing day; a mismatch makes the NOW wait and fail visibly with "no GOB data", never complete with wrong data. Discussions with the tech lead and architect continue. Consequence: a first share and its reshares on the same hearing day share one id; the latest hand-over for the day replaces the kept data.
- **Answers (developer, 2026-10-09, second round)**:
  1. Results not attached to an offence (defendant or defendant-case level) are also sent to GOB at most once.
  2. An offence that is adjourned is **not** sent to GOB; only its final result is sent, in the share where it is resulted.
  3. A later share with nothing new for GOB reuses the most recent GOB data, as an amend & reshare does.
  4. The existing NOWS flow may issue NOWS again on a later share; when no GOB call will follow for that share (everything already sent and handed over), those NOWS are generated with the most recent GOB data received.
- **Finding (verified)**: the result categories in reference data do not identify "final" results for enforcement: Enforcement Summons, Committal Warrant Notice and AEC are intermediary, Collection Order is ancillary. "Adjourned" is therefore recognised from the offence's results (a next hearing, or an adjournment result code), not from the category (research R19, OQ-13).
- **Rationale (verified)**: the existing flow sends its own financial-imposition update towards GOB only for results marked as financial impositions (new fines, costs, compensation). Enforcement hearing results act on an existing GOB account and are not financial impositions (e.g. Collection Order, Money Payment Supervision Order, Enforcement Summons, Committal Warrant Notice are all non-financial in reference data), so GOB is updated only by the enforcement flow (FR-013), and the existing flow needs no change.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Enforcement NOWS are generated with GOB's data, whichever flow arrives first (Priority: P1)

When an Enforcement case is resulted, GOB accepts the result, and Common Platform decides NOWS are needed, the defendant's NOWS are generated containing the account information GOB returned (for example the account number, balance outstanding, terms to pay and warrant details) together with the hearing and case details, regardless of whether GOB's data or the NOW reached the NOWS service first, and delivered as NOWS are today.

**Why this priority**: This is the purpose of requesting data from GOB (CCT-1222 FR13). Without it, enforcement NOWS cannot be produced on Common Platform, and because the two flows race, a design that handles only one order would lose NOWS at random.

**Independent Test**: Share an Enforcement case result that GOB accepts and that calls for a NOWS. Run it twice: once with the NOW arriving at the NOWS service before GOB's data, once after. In both runs, confirm one document per applicable NOW, with GOB's section exactly as GOB returned it, stored and distributed.

**Acceptance Scenarios**:

1. **Given** an enforcement NOW is waiting for GOB's data, **When** the enforcement workflow hands over GOB's data for that share and defendant, **Then** the NOW is completed with that data and generated.
2. **Given** the enforcement workflow has handed over GOB's data and no NOW for that share and defendant has arrived yet, **When** the NOW arrives, **Then** it is completed with the kept data immediately, without waiting.
3. **Given** GOB returned only some of the requested data items (or none), **When** the NOW is generated, **Then** it uses what GOB returned; missing items are left empty, never invented.
4. **Given** a completed enforcement NOW, **When** generation finishes, **Then** it is stored against the case and distributed using the existing NOWS distribution rules.
5. **Given** a defendant for whom Common Platform creates several enforcement NOWS, **When** GOB's data is handed over (before or after them), **Then** every one of those NOWS is completed with the same data, each generated once.
6. **Given** a NOW for a case that is not an Enforcement case, **When** it arrives, **Then** it is processed exactly as today, without waiting.

---

### User Story 2 - The hand-over is recorded and never re-sends to GOB (Priority: P2)

Every hand-over of GOB's data to the NOWS service is recorded with its outcome, so support can see whether GOB's data reached the NOWS service for a defendant, and so a later error-handling feature can replay it. A failed hand-over never causes the result to be sent to GOB again.

**Why this priority**: GOB has already updated the account. Re-sending would double-update a live account (feature 001, constitution V), and losing the hand-over silently would leave the defendant's NOWS waiting until they fail.

**Independent Test**: Make the NOWS service accept and then reject a hand-over; confirm each outcome is recorded against the submission, and that GOB received exactly one submission in both cases.

**Acceptance Scenarios**:

1. **Given** GOB accepted a submission, **When** the NOWS service accepts the hand-over (whether or not a NOW is waiting), **Then** the outcome "handed over to NOWS" is recorded against that submission.
2. **Given** the NOWS service rejects the hand-over or cannot be reached, **When** the attempt ends, **Then** the outcome is recorded as failed with the reason, nothing is re-sent to GOB, and later results continue to be processed.
3. **Given** a recorded hand-over, **When** the NOWS service completes a NOW, **Then** it uses the data it was handed; it never reads the submission record.

---

### User Story 3 - Each NOW receives the right data, exactly once (Priority: P2)

Each enforcement NOW is completed once, with its own hearing and defendant's data only, even if the hand-over is repeated, NOWS arrive in any order, or several hearings and defendants are in progress at the same time.

**Why this priority**: A NOW completed with another defendant's account data, or generated twice, is a court document sent to the wrong person or duplicated: a data-protection and service failure.

**Independent Test**: Hand over the same data twice; process two defendants and two hearings with NOWS and hand-overs interleaved in different orders; confirm each NOW is generated once with only its own data.

**Acceptance Scenarios**:

1. **Given** the same hand-over delivered twice, **When** the second arrives, **Then** it has no effect (no second document, no change to a completed NOW).
2. **Given** hand-overs and NOWS for two defendants or two hearings, interleaved, **When** they are processed, **Then** each NOW is completed only with its own hearing and defendant's data.
3. **Given** a hand-over and a NOW for the same hearing and defendant arriving at nearly the same moment, **When** both are processed, **Then** the NOW is completed exactly once.

---

### User Story 4 - Nothing waits forever unnoticed (Priority: P3)

When one of the two flows never arrives, the other does not wait or linger indefinitely: a waiting NOW fails visibly after a maximum wait, and kept GOB data that no NOW ever claims is released after a retention period.

**Why this priority**: GOB may not accept a result (no hand-over), the NOWS decision may need no NOWS (no NOW), or a component may fail. Support needs to see NOWS that could not be produced; unclaimed GOB data must not be kept longer than necessary.

**Independent Test**: With shortened periods, (a) send a NOW for a defendant whose result GOB rejected, and (b) hand over GOB data for a hearing for which no NOWS are created; confirm (a) the NOW fails with "no GOB data" and (b) the kept data is released and recorded as unclaimed.

**Acceptance Scenarios**:

1. **Given** a result GOB rejected, failed, timed out on, or that was never sent because it was unsendable, **When** processing ends, **Then** nothing is handed over and no enforcement NOWS is generated for that defendant.
2. **Given** an enforcement NOW waiting for GOB's data, **When** the maximum wait passes without a hand-over, **Then** the NOW is recorded as failed with the reason "no GOB data", visible to support, and can be completed later by the error-handling feature (CIMD-4259).
3. **Given** kept GOB data that no NOW has claimed, **When** the retention period passes, **Then** the data is released (removed) and the hand-over is recorded as unclaimed; this is not an error. Data a NOW has claimed is also released when its retention period passes (Common Platform may have decided no NOWS were needed).

---

### User Story 5 - Amend & reshare NOWS use the GOB data already received (Priority: P3)

When a hearing of an Enforcement case is amended and reshared and Common Platform decides an enforcement NOWS is needed, GOB is not contacted. A NOWS for a result of the same type as one already sent to GOB is generated with the most recent GOB data received; a NOWS for a completely different result is not generated, because GOB never provided the data it needs, and is visible to support.

**Why this priority**: CCT-1222 requires new NOWS on amend & reshare for some results (e.g. Collection Order); GOB is never sent a second request, so only the GOB data already received can be used (BA/BPO, 2026-10-08).

**Independent Test**: Share an Enforcement case result that GOB accepts, then amend and reshare it (a) changing a result of the same type, (b) adding a completely different result; confirm GOB is contacted once, (a)'s NOWS carry the GOB data already received, in either arrival order, and (b)'s NOWS are not generated and show the reason.

**Acceptance Scenarios**:

1. **Given** a first share accepted by GOB, **When** the hearing is amended and reshared, **Then** GOB is not contacted and the most recent GOB data received for that case and defendant is handed over again under the hearing day's share correlation id, together with which results it covers.
2. **Given** that hand-over, **When** Common Platform's enforcement NOWS for the reshare arrive (before or after it) for a result of the same type as one sent to GOB, **Then** they are completed with that GOB data.
3. **Given** that hand-over, **When** an enforcement NOWS for the reshare is for a completely different result (none of its results was sent to GOB), **Then** it is not generated and is recorded as failed with the reason "result not sent to GOB", visible to support.
4. **Given** a first share that GOB did not accept, **When** the hearing is reshared, **Then** nothing is handed over and any enforcement NOWS for the reshare fail with "no GOB data" after the maximum wait.

---

### User Story 6 - Each offence is sent to GOB once; later shares send only what is new (Priority: P1)

A hearing of an Enforcement case may be resulted in several shares: some offences are resulted while others are adjourned, and the adjourned offences are resulted at a later share with the same hearing id. Each share that results offences not yet sent to GOB is sent to GOB with those offences' results only; an offence already sent is never sent again, even when the later hearing-resulted payload repeats its results. Each share's GOB data is handed over and completes that share's enforcement NOWS.

**Why this priority**: today a later share of the same hearing is treated as a repeated delivery and never reaches GOB, so the adjourned offence's result is lost; and re-sending an already-sent offence would double-update a live GOB account.

**Independent Test**: Share a hearing resulting offence 1 and adjourning offence 2; then share it again resulting offence 2 (the payload still contains offence 1's results). Confirm GOB receives two submissions, the first with offence 1's results only, the second with offence 2's results only, and that each share's enforcement NOWS are completed with that share's GOB data.

**Acceptance Scenarios**:

1. **Given** share 1 resulted offence 1 and adjourned offence 2, **When** share 2 of the same hearing results offence 2 and repeats offence 1's results, **Then** GOB receives offence 2's results only, as a new submission.
2. **Given** offences already sent to GOB in earlier shares, **When** a later share contains only those offences' results, **Then** GOB is not contacted, the outcome "nothing new to send" is recorded, and the most recent GOB data for the case and defendant is handed over again (as for a reshare), so the NOWS the share produces are generated with it.
3. **Given** an offence adjourned in share 1, **When** share 1 is processed, **Then** that offence is not sent to GOB (even if one of its results has a GOB result code), and **When** a later share gives its final result, **Then** it is sent then.
4. **Given** the same share delivered twice, **When** the second delivery arrives, **Then** it has no effect (same share correlation id).
5. **Given** two hearing days of the same hearing and defendant, each handed over, **When** their enforcement NOWS arrive (in any order relative to the hand-overs), **Then** each NOW is completed with the GOB data of its own share.
6. **Given** any number of offences resulted across any number of shares (and hearings) of a case, **When** all are processed, **Then** each offence's results were sent to GOB exactly once.

---

### Edge Cases

- **NOW arrives first / hand-over arrives first / both at once**: all handled (US1, US3).
- **Several enforcement NOWS for one defendant**: one hand-over completes all of them, whenever they arrive within the retention period (US1).
- **Duplicate hand-over** (repeated delivery, replay): no effect (US3).
- **GOB did not accept the result**: no hand-over; the waiting NOW fails after the maximum wait with "no GOB data" (US4).
- **Common Platform decides no NOWS are needed**: the hand-over's data is kept, then released after the retention period, recorded as unclaimed (US4).
- **A NOW arrives after its kept data was released** (NOWS flow delayed beyond the retention period): it waits and, after the maximum wait, fails with "no GOB data".
- **Reshare / amend and reshare**: never sent to GOB, in any case. If GOB accepted an earlier submission for the case and defendant, the enforcement workflow hands over the most recent GOB data again under the hearing day's correlation id; reshare NOWS for results of the same type are completed with it, and NOWS for completely different results fail with "result not sent to GOB" (FR-016, FR-017). If the first share was not accepted by GOB, there is nothing to hand over and they fail with "no GOB data".
- **Several reshares (same hearing day)**: they share the hearing day's correlation id; each reshare hand-over with a later share time replaces the kept data for that day and defendant (latest wins, covered results accumulated, retention restarted); a repeated delivery has no effect.
- **Later share of the same hearing** (adjourned offences resulted on a later hearing day, same hearing id): a first share for the offences it newly results; sent to GOB with those offences only; its own correlation id, from its hearing day (US6).
- **Offence results repeated in a later payload**: never re-sent (FR-018).
- **Offence adjourned in a share**: not sent and not counted as sent, whatever its results' codes; sent in the share that gives its final result (FR-018, US6 scenario 3).
- **Results not attached to an offence** (defendant or defendant-case level): each sent at most once (FR-018).
- **Later share with nothing new for GOB** (all offences already sent, or the rest adjourned): GOB not contacted; the most recent GOB data for the case and defendant is handed over again, so NOWS the existing flow issues for that share are generated with it ; with no earlier GOB data, nothing is handed over and such NOWS fail with "no GOB data".
- **Hearing day missing on the event**: no correlation id can be built; the share is not processed and the reason is logged for support (as for a missing reshare flag).
- **Shared time missing on the event**: the share cannot be recorded or handed over (the hand-over carries it); not processed, reason logged for support.
- **Later share with no GOB-recognised result** (e.g. another hearing of the case resulting only results GOB does not accept): recorded as having no result code; the most recent GOB data is handed over as for FR-020.
- **Same offence in another hearing** (adjourned to a new hearing id): also never re-sent; the rule is per offence, not per hearing.
- **Emails** (to the Enforcement or Confiscation team on amend & reshare, and for some result codes on the first share): not part of this feature (notification story).
- **GOB reply for a different case reference** (feature 001 logs a warning): the data is handed over for the hearing and defendant it was requested for.
- **Partial or empty GOB data**: the NOW is generated with what was returned (US1).
- **No template for a NOW**: handled by the existing NOWS behaviour for generation failures; the failure is visible to support.
- **NOWS service unavailable at hand-over**: recorded as failed (US2); the NOW waits and then fails with "no GOB data"; replay belongs to CIMD-4259.
- **Welsh-language court**: the existing bilingual-template rule applies; GOB's Welsh fields (cymraeg_*) are carried unchanged.

## Requirements *(mandatory)*

### Functional Requirements

**Hand-over (enforcement workflow)**

- **FR-001**: When GOB accepts a submission and returns its reply, the system MUST hand GOB's account data to the NOWS service in the same processing of that result, without waiting for a further event.
- **FR-002**: The hand-over MUST carry GOB's NOWS data items exactly as GOB returned them (no field added, removed, renamed or reformatted), the GOB account number, the identifiers of the hearing, case, defendant and case reference the submission was for, the hearing day, the share time and the share correlation id (FR-021).
- **FR-003**: The system MUST record the outcome of every hand-over (accepted by the NOWS service, or failed with the reason) against its submission. The submission record is write-only for the NOWS service: no step of NOWS generation reads it. The only read is by the enforcement workflow itself, to hand over the most recent GOB data again on a reshare (FR-016).
- **FR-004**: A failed hand-over MUST NOT cause the result to be sent to GOB again, and MUST NOT stop the processing of later results. Replaying a hand-over is out of scope (future error handling), but the record MUST hold everything a replay needs.
- **FR-005**: The NOWS service MUST accept hand-overs only from the enforcement workflow (an authorised system identity), through an operation dedicated to enforcement (tech lead, 2026-10-09).

**Bringing the two flows together (NOWS service)**

- **FR-006**: A NOW for an Enforcement case's defendant MUST NOT be generated until GOB's data for that share and defendant is available. If it is not yet available when the NOW arrives, the NOW MUST wait. NOWS for non-Enforcement cases MUST be processed exactly as today.
- **FR-007**: Which NOWS are needed, and their content apart from GOB's data, MUST remain decided and built by Common Platform's existing NOWS flow (from the hearing results and reference data); this feature MUST NOT create NOWS of its own.
- **FR-008**: When a hand-over arrives, the NOWS service MUST complete every NOW waiting for that share and defendant. When no NOW is waiting, it MUST keep GOB's data so that any NOW for that share and defendant arriving later is completed immediately.
- **FR-009**: Completing a NOW MUST place GOB's data unchanged into the NOW's document data as the enforcement NOWS data block (the structure of reference change CIMD-4462, identical to GOB's contract), and the GOB account number in the existing account number field; after that, document generation, storage against the case and distribution MUST follow the existing NOWS steps unchanged, including the existing Welsh-language rule.
- **FR-010**: GOB's data MUST be matched to NOWS by share correlation id (FR-021) and defendant, so each NOW receives only its own share's and defendant's data, also when one hearing has several shares. A repeated hand-over MUST have no effect, a later hand-over for the same hearing day and defendant (a reshare) MUST replace the kept data, and a NOW MUST be completed exactly once even when the NOW and the hand-over arrive at the same moment.
- **FR-011**: A NOW still waiting after a configurable maximum wait MUST be recorded as failed with the reason "no GOB data", visible to support. Kept GOB data MUST be released after a configurable retention period, whether or not a NOW claimed it; data no NOW claimed is recorded as unclaimed (not an error).
- **FR-012**: When GOB did not accept the result (rejected, failed, outcome unknown, or never sent because unsendable), nothing MUST be handed over and no enforcement NOWS MUST be generated without GOB's data; the waiting NOW fails as in FR-011, and the error-handling feature (CIMD-4259) can complete it later.

**Amend & reshare (decided 2026-10-08)**

- **FR-016**: An amend & reshare MUST NOT be sent to GOB, in any case. When GOB accepted at least one earlier submission for the case and defendant, the enforcement workflow MUST hand over the most recent GOB data it received for them (unchanged), marked as a reshare, under the share correlation id of its hearing day, together with the reshare's results that are of the same type as results already sent to GOB (the results the GOB data covers). If GOB accepted no earlier submission, nothing MUST be handed over.
- **FR-017**: For an amended NOW (amend & reshare), the NOWS service MUST complete an enforcement NOWS with the handed-over GOB data only when the NOWS is for at least one result covered by it; a reshare NOWS for completely different results MUST NOT be generated and MUST be recorded as failed with the reason "result not sent to GOB", visible to support. Matching and either-order rules are as for a first share (FR-008 to FR-011).

**Later shares and offences (decided 2026-10-09)**

- **FR-018**: Each offence's results MUST be sent to GOB at most once, across all shares and hearings of the case, whether or not a later hearing-resulted payload repeats them, and only when the offence is finally resulted: an offence adjourned in a share MUST NOT be sent in that share. An offence counts as sent once a submission that includes at least one of its GOB-recognised results has been sent to GOB, whatever GOB's answer, or the send was attempted and failed before reaching GOB (that submission is replayed, CIMD-4259; a later share never re-sends it) (feature 001: never re-sent). An adjourned offence, or one whose results were all left out (no GOB-recognised result), does not count as sent. Results not attached to an offence MUST likewise be sent at most once.
- **FR-019**: A share that is not a reshare MUST be processed for GOB even when an earlier share of the same hearing was sent: its submission MUST include only the results of offences not yet sent (FR-018). A repeated delivery of the same share MUST have no effect.
- **FR-020**: When a share has no result left to send after FR-018 (including a share with no GOB-recognised result at all, recorded as such), GOB MUST NOT be contacted and the outcome "nothing new to send" MUST be recorded; the enforcement workflow MUST hand over the most recent GOB data received for the case and defendant, as for an amend & reshare (FR-016, FR-017), so that NOWS issued for that share are generated with it. With no earlier GOB data, nothing MUST be handed over.
- **FR-021**: Each share MUST be identified by a share correlation id: a UUID generated deterministically from the hearing id and the **hearing day** (interim decision 2026-10-09, to be revisited), by the same documented rule in the enforcement workflow and in the NOWS service, so both derive the same id without exchanging it first. A first share and its reshares on the same hearing day have the same id; another hearing day has its own. The enforcement workflow MUST use it to recognise repeated deliveries and to key the hand-over; the NOWS service MUST use it to match NOWS and hand-overs, taking the hearing day of a NOW from its order date.

**Both**

- **FR-013**: Common Platform's existing NOWS flow MUST NOT be changed by this feature. For Enforcement cases, GOB MUST be updated by the enforcement flow only, once per offence (FR-018); this holds because the existing flow sends its own financial-imposition update only for financial-imposition results, which enforcement hearing results are not (see Clarifications and Assumptions).
- **FR-014**: Neither service MUST write defendant personal data or GOB account data to application logs; logs carry identifiers, the case reference, statuses and outcomes only (feature 001 FR-017).
- **FR-015**: GOB's account data kept or stored by the NOWS service MUST follow a recorded data-protection decision (retention, at-rest handling) before production use; the retention period in FR-011 is part of that decision.

### Key Entities

- **GOB account data (NOWS data items)**: the account information GOB returns for a submission: defendant account details, payment and transaction history, offences and impositions with creditors, terms to pay, balance, warrant number and contact details, bail amount, CT bank details, days before release of warrant, account number, date imposed. Optional item by item.
- **Share**: one sharing of a hearing's results for a hearing day (a first share, a later hearing day's share with newly resulted offences, or a reshare), correlated by the share correlation id (hearing id + hearing day; reshares of a day share it).
- **Sent offence**: the record that an offence's results have been sent to GOB, in which submission; it prevents any second sending (FR-018).
- **Hand-over**: one delivery of a share's GOB account data to the NOWS service, with the share correlation id, hearing, case, defendant and case reference. It completes the NOWS waiting for that share and defendant, or is kept until they arrive; outcomes: completed NOWS, kept, unclaimed (released), or failed (recorded on the submission).
- **Enforcement NOW**: a NOW created by Common Platform's existing NOWS flow for an Enforcement case's defendant. States: waiting for GOB data → completed (then generated, stored, distributed as today), or failed ("no GOB data", "result not sent to GOB", "no master defendant id", or the existing generation failures).
- **Submission record** (feature 001): what was sent to GOB, GOB's reply, the outcome and now the hand-over outcome; write-only for this flow.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of submissions accepted by GOB result in a hand-over attempt with a recorded outcome.
- **SC-002**: 100% of enforcement NOWS for which both flows arrive within the configured periods are generated with GOB's data, in either arrival order (0 lost to ordering).
- **SC-003**: 100% of enforcement NOWS generated contain GOB's data exactly as returned (0 altered or invented values).
- **SC-004**: 0 submissions are re-sent to GOB because of a NOWS-side failure, and 0 changes are made to the existing NOWS flow.
- **SC-005**: Each enforcement NOW is generated at most once (0 duplicates), and 0 NOWS contain another hearing's or defendant's data.
- **SC-006**: 100% of enforcement NOWS that cannot be completed (no GOB data within the maximum wait, or a generation failure) are visible to support with a reason.
- **SC-007**: NOWS for non-Enforcement cases show no change in behaviour; with the feature switched on, at most one case lookup is added to their processing, and none when the NOW already identifies its prosecuting authority. With the feature switched off, nothing changes.
- **SC-009**: 0 offences have their results sent to GOB more than once; 100% of offences newly resulted with a GOB result code in a later share of the same hearing are sent to GOB.
- **SC-008**: Under normal conditions, an enforcement NOW is generated within 10 minutes of the later of the two flows reaching the NOWS service.

## Assumptions

- **Scope**: the NOWS generation for enforcement results (CCT-1222 FR13). The outstanding-fines display (FR17), new delivery routes (enforcement team email by GOB MET ID / division code, Confiscation email reference data) and the reshare email are separate stories.
- **NOWS selection and content**: decided and built by Common Platform's existing NOWS flow from reference-data NOW definitions; the enforcement NOW definitions are being configured under CCT-1222 (CIMD-4331 sub-tickets). Final template names, and whether S136 / CLAMPO produce NOWS or email only, are confirmed with the BA and the template developer.
- **Templates**: designed on the document generator by another developer; this feature supplies GOB's data to the NOWS.
- **Data model**: the enforcement NOWS data block follows reference change CIMD-4462 (hearing NOWS PR #186, not merged), whose structure is identical to GOB's contract; it will be implemented in this feature.
- **Recognising an Enforcement case's NOW**: the NOW the NOWS service receives carries its prosecution cases' prosecuting authority code (filled by Common Platform's existing NOWS flow); when it is missing, the NOWS service obtains it from the case details it can already query. The NOW is an Enforcement case's when the authority is the Enforcement authority (default GAPGD00, configurable, as in feature 001).
- **Financial results in an enforcement hearing**: if an Enforcement case's hearing also imposes a genuinely new financial order (e.g. new costs), the existing flow treats it as it does today (its own imposition and financial NOW); confirm with the BA that this cannot conflict with the enforcement flow's GOB update.
- **Default periods**: maximum wait for a NOW and retention of unclaimed GOB data both default to 24 hours, configurable (the two flows normally meet within minutes; a day leaves room for incidents). Final values are part of the data-protection decision (FR-015).
- **Reshare NOWS**: decided 2026-10-08 (FR-016, FR-017). "Same type of result" means a result whose GOB result code was in a request already sent to GOB for that case and defendant; a reshare NOWS is completed if at least one of its results is such a result (to confirm with the BA).
- **Notifications**: emails to the Enforcement or Confiscation team (selected by the related reference's MET ID prefix = Division Code), on amend & reshare and for some result codes on the first share, are delivered by the notification story; this feature only records why a NOWS was not generated.
- **Rollout**: the NOWS service's enforcement behaviour is switched on (feature toggle) together with the enforcement workflow's hand-over configuration in each environment; with it off, enforcement NOWS are processed as today, without GOB data.
- **Which NOWS data GOB returns** depends on feature 001's short-code-to-data-item mapping (still empty until the Confluence rows arrive; the previous HLD §8.2 proposes rules). Until then GOB returns no data items, and NOWS carry the account number only.
- **Previous HLD** (Result Sharing High Level Design, v0.1, May 2026; `specs/docs/`) is reference only: it predates the gateway route and assumed an event publication instead of the agreed synchronous hand-over; its NOWS retry principle (§7.6: retry the NOWS step from stored GOB data, never re-submit to GOB) is kept.
- **Error handling**: retries and replay are out of scope (CIMD-4259); this feature records outcomes so they are possible later.
- **Single instance** of the enforcement workflow, as in feature 001.
- **Correlation attribute in the NOWS service** (interim, research F17, OQ-9): the hearing day; the NOWS service reads it from the NOW's order date, which the existing NOWS flow derives from the ordered dates entered in the court UI. Not guaranteed to equal the hearing day: a mismatch leads to a visible "no GOB data" failure. A guaranteed attribute is to be agreed with the tech lead and architect and may replace it later.
- **Recognising an adjourned offence**: an offence is adjourned in a share when an adjournment result (one carrying a next hearing, flagged as an adjournment, or with a code in a configurable list, default NEXH and ADJNN) is among its most recently ordered results in that share; because payloads repeat earlier days' results, an offence adjourned on an earlier day and not resulted since stays adjourned, while an earlier adjournment followed by a later result does not count. Ordered dates are entered by hand (research F17), so a wrongly dated result can misclassify an offence; to confirm with the BA, including that GOB's own adjournment code ADJNN is never sent (research OQ-13).
- **Feature 001 data**: feature 001 has run in test environments only; its existing submission records need no conversion beyond what the migration states (data-model §1).
- **Feature 001 alignment**: feature 001's FR-012 and SC-005 ("GOB's reply is stored so the NOWS step can read it") are superseded by the write-only decision; feature 001's documents are updated to match.
- **Dependencies**:
  - The hearing NOWS service (owned by another team) must accept the hand-over, keep early data, recognise and hold Enforcement-case NOWS until completed, and carry GOB's data to the document generator in the CIMD-4462 data model; these changes are part of this feature and follow that service's conventions and owning team's review, as allowed by constitution v1.1.0.
  - Common Platform's existing NOWS flow (the PaaS NOWS functions) is used as it is; the enforcement NOW definitions it relies on are configured under CCT-1222 (CIMD-4331).
