# Feature Specification: Triggering NOWS Generation for Enforcement Hearings

**Feature Branch**: `dev/CIMD-3063` (continued; the developer manages branches)
**Jira**: CIMD-4530 (part of CCT-1222)
**Created**: 2026-10-19
**Status**: Draft (based on the architect's design "Triggering NOWs Generation for Enforcement Hearings", latest **non-final** version 20/10/2026, reviewed 2026-10-20; re-check when it is final; product decisions of 2026-10-22 applied). Implementation proceeds in iterations; edge-case clarifications are added as they are agreed (tech lead, 2026-10-20).
**Input**: User description: "CIMD-4530: Triggering NOWs generation for enforcement hearings (redesign of CCT-1222 enforcement flow). Function App calls the enforcement workflow with hearingId, hearingDate and the NOW requestIds it created; workflow fetches results (cache, else Results), applies existing enforcement selection, sends once per offence to GOB/Libra, publishes a new public event with the requestIds and full GOB nowsDataItems to hearingnows, which matches held NOWs on requestId and resumes generation."

**Sources**: `specs/docs/DATAIN-Triggering NOWs Generation for Enforcement Hearings.-201026-144614.pdf` (architect, latest,
not final; supersedes `…-191026-141134.pdf`),
`specs/docs/CCT-1222-design-change-analysis.md` (pre-spec analysis, 2026-10-16), the developer's answers of 2026-10-19.

**Relationship to earlier features**: this feature changes **how** the enforcement flow is triggered, **how** each result
reaches GOB once, and **how** GOB's data reaches the NOWS service. It keeps, unchanged, what features 001 and 002 decided
about selecting Enforcement cases, building and sending the GOB submission, recording outcomes and completing NOWS with
GOB's data. Requirements of 001 and 002 that this feature replaces are listed in "Superseded requirements" below; all
others still apply.

## Terms

- **Results-processing app**: the existing serverless application that reacts to every hearing-results share, decides
  which NOWS are needed and creates them in the NOWS service (called "Function App" in the architect's design).
- **Enforcement workflow**: the service that holds all enforcement decision logic and talks to GOB through the
  enforcement gateway (features 001, 002).
- **NOWS service**: the Common Platform service that holds NOW requests and generates NOW documents (`hearingnows`).
- **Request id**: an identifier the results-processing app creates for each enforcement NOW request it creates, used
  unchanged from start to finish to tie GOB's data back to that NOW.

## Clarifications

### Session 2026-10-19

- Q: How does the results-processing app prove its identity to the enforcement workflow? → A: Internal network only,
  like its calls to Common Platform contexts (system user identity on the call); not through the external API gateway
  (FR-003).
- Q: What happens to held NOWS when GOB rejects the submission or the outcome is unknown? → A: They stay held, as when
  nothing is sent; visible failure or later completion is handled by the error-handling story CIMD-4259 (FR-016).
- Q: How is the switch from the current event listener to the new notification made? → A: The listener is removed
  in this feature; from the release onwards the notification is the only trigger (FR-005).
- Q: How are stored failures replayed? → A: Automatically, on a longer schedule up to a configured limit, and
  manually by support at any time (FR-015).
- Q: What happens to a GOB data event that matches no held NOW (it arrives before the NOW)? → A: The NOWS service
  keeps the GOB data by request id for a configurable retention period; a NOW with that request id arriving within it
  is completed at once; afterwards the data is released (FR-013).
- Q: What does the results-processing app do when it cannot deliver the notification? → A: It retries as its
  NOW-posting step does; on final failure its run is marked failed, visible and restartable (FR-002a).
- Q: Which NOWS for an Enforcement case are held? → A: All of them, including NOWS that need no GOB data; each is
  held until GOB's response is passed on (FR-001).
### Session 2026-10-20 (architect's version of 20/10/2026)

- Changes against the 19/10 draft: diagrams, flow steps, rules, exception flow unchanged. Amend & reshare scenario 3
  (same result type, different prompt values, different GOB data needed) is marked **not valid**: it cannot occur, so no
  requirement covers it. Scenario 4 (different result type needing different GOB data, NOW missing data): the
  Enforcement team produces the NOW **manually** with their existing process (upload to CP, update GOB) — outside this
  feature. The "For discussion" item is now titled **"Multiple results: one right, one wrong"** (a share with a result
  outside the enforcement result-code set: no message out, send on the corrected reshare); still under discussion, so
  the interim rule was FR-010a (withdrawn 2026-10-22, see below).

### Session 2026-10-22 (product decisions)

- Q: Which results are sent to GOB? → A: All of them, irrespective of the curated list of result codes GOB accepted
  (GOB's result-code list); agreed with the GOB/Libra team. Only a result with no code (unknown in reference data), or
  whose code is longer than the contract's 10 characters (never truncated), MUST be left out and recorded (FR-010).
  Supersedes FR-010a and the "Multiple results: one right, one wrong" item.
- Q: Is an amend & reshare sent to GOB when the first result was not sent before? → A: No. An amend & reshare is never
  sent to GOB, even when the first result never reached GOB (FR-008). Supersedes the 2026-10-20 "first received is sent"
  rule for reshares.
- Q: How does the enforcement workflow know that a share is an amend & reshare? → A: The results-processing app sends
  the reshare indicator in the notification (the Results service does not provide it) (FR-002).
- Q: What does the enforcement workflow do for an amend & reshare? → A: It only passes GOB data on to the NOWS service:
  the most recent GOB data received for the case and defendant, with the reshare's request ids. With no earlier GOB data
  nothing is sent and the reshare's NOWS stay held (FR-009, FR-016).
- Q: How long does a NOW that reached the NOWS service first wait for the GOB data? → A: Without a time limit (feature
  002's 24-hour maximum wait is removed) (FR-016).
- Q: Is the email on amend & reshare built here? → A: No; an email is sent on amend & reshare, as its own story.
- Q: Who decides whether NOWS are distributed or suppressed? → A: The existing subscription rules, with no manual
  suppression; the first-time result decides for an amend & reshare: NOWS suppressed the first time stay suppressed,
  NOWS distributed the first time are distributed again (FR-021).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Enforcement NOWS generated with GOB data, triggered by the results-processing app (Priority: P1)

When a court shares results for an enforcement hearing, the results-processing app creates the NOW requests as today,
gives each enforcement NOW a request id, holds them, and tells the enforcement workflow which hearing, hearing date and
request ids are involved. The enforcement workflow gets the hearing results, sends them to GOB once, and passes GOB's
account data back to the NOWS service with the same request ids. The NOWS service finds the held NOWS by request id,
adds GOB's data and generates them as today.

**Why this priority**: this is the whole purpose of the change. Without it, enforcement NOWS are not generated with
GOB's account data.

**Independent Test**: share results for an Enforcement case with a result that needs a NOW and GOB data; verify one
submission reaches GOB, one message with GOB's data and the request ids reaches the NOWS service, and the NOW is
generated with GOB's data unchanged.

**Acceptance Scenarios**:

1. **Given** an enforcement hearing is shared and the results-processing app has created two enforcement NOWS with
   request ids R1 and R2, **When** it informs the enforcement workflow with the hearing, hearing date, R1 and R2,
   **Then** the enforcement workflow sends the results to GOB once and, after GOB accepts, sends GOB's data with R1 and
   R2 to the NOWS service, which completes and generates both NOWS.
2. **Given** the NOWS are held waiting, **When** GOB's data for their request ids arrives, **Then** each NOW is
   completed exactly once with GOB's data placed unchanged, and generation, storage and distribution follow the
   existing steps.
3. **Given** a share for a non-Enforcement case, **When** the results-processing app processes it, **Then** it does not
   inform the enforcement workflow and the NOWS are processed exactly as today.
4. **Given** the enforcement workflow is informed for a hearing whose results contain no Enforcement case, **Then**
   nothing is sent to GOB, the reason is recorded, and no message is sent to the NOWS service.

---

### User Story 2 - Each offence's first-time results reach GOB once; amend & reshare never (Priority: P1)

GOB accepts results for a case hearing only once. The first-time result of an offence is sent to GOB, with all its
result codes. An amend & reshare is never sent to GOB, even when the first-time result never reached GOB; its NOWS are
still generated with the GOB data received for the first-time result, or stay held when there is none (decisions of
2026-10-22).

**Why this priority**: sending a result twice is rejected by GOB and sending the wrong version leaves GOB wrong; both
need manual correction by the Enforcement team.

**Independent Test**: deliver a first share and then an amend & reshare for an offence; verify only the first share is
sent to GOB and the reshare's NOWS are generated with the data GOB returned for the first share. Deliver an amend &
reshare with no first-time result sent; verify nothing is sent to GOB or to the NOWS service and its NOWS stay held.

**Acceptance Scenarios**:

1. **Given** no result of an offence has been sent to GOB, **When** a first-time share with a result for it is
   processed, **Then** it is sent to GOB with all its result codes, including codes that are not on GOB's result-code
   list.
2. **Given** an offence's results were already sent to GOB, **When** an amend & reshare for it is processed, **Then**
   nothing is sent to GOB, and the GOB data received for the earlier send is sent to the NOWS service with the reshare's
   request ids so its NOWS are generated.
3. **Given** the first-time result of an offence never reached GOB (rejected, failed, or not yet arrived), **When** an
   amend & reshare for it is processed, **Then** nothing is sent to GOB or to the NOWS service, the outcome is recorded,
   and the reshare's NOWS stay held without a time limit; a delayed first-time share arriving later is still sent.
4. **Given** a first-time share of a later hearing day whose offences were all sent already, **When** it is processed,
   **Then** nothing is sent to GOB and the most recent GOB data is sent to the NOWS service with its request ids.
5. **Given** the same notification is delivered twice, **Then** the second has no effect (no second GOB submission and
   no second message to the NOWS service).

---

### User Story 3 - No message is lost when a step fails (Priority: P1)

Any step can fail for a while (results not yet available, GOB gateway down, messaging down). The enforcement workflow
retries each failing stage and, if it still fails, keeps the work so that it can be replayed later. Getting the results,
building the GOB request and sending it is one stage; sending GOB's data to the NOWS service is a second stage, so a
failure there never causes a second GOB submission.

**Why this priority**: the architect's design states "no message may be lost"; a lost notification leaves NOWS held
for ever and GOB without the result.

**Independent Test**: make the results unavailable, then the messaging unavailable, while processing a share; verify
retries, a stored record of the failed stage, and that a later replay completes it without a second GOB submission.

**Acceptance Scenarios**:

1. **Given** the results are not yet available, **When** the enforcement workflow is informed, **Then** it accepts the
   notification and retries getting them; when they become available within the retries, processing completes.
2. **Given** a stage still fails after 3 attempts with back-off, **Then** the work is stored with the stage, the reason
   and everything needed to replay it, and it is visible to support.
3. **Given** a stored failure, **When** the cause clears, **Then** it is replayed automatically on the longer schedule
   (or earlier when support replays it) and completes; past the configured limit it stays stored and visible.
4. **Given** GOB accepted the submission but sending GOB's data to the NOWS service fails, **When** it is replayed,
   **Then** only GOB's data is sent again (GOB is not contacted again).
5. **Given** a crash happens after a request was sent but before its outcome was saved, **Then** the outcome is recorded
   as unknown and visible to support (accepted residual risk).

---

### User Story 4 - Support can see what happened to each notification (Priority: P2)

Support staff can find, for any hearing and request id, whether the enforcement workflow received the notification,
what was sent to GOB (or why nothing was), GOB's answer, whether GOB's data was sent to the NOWS service, and any
stored failure awaiting replay.

**Why this priority**: needed to operate the flow and to support the later manual correction story, but the flow
delivers value without new tooling.

**Independent Test**: process successful, skipped and failed notifications; verify each has a recorded outcome
findable by hearing and request id.

**Acceptance Scenarios**:

1. **Given** any notification was received, **Then** its outcome (sent to GOB, not sent with the reason, GOB data
   reused, failed with the stage and reason) is recorded against the hearing and its request ids.

---

### Edge Cases

- **Reshare before first share**: the reshare is not sent to GOB; with no earlier GOB data its NOWS stay held; the
  delayed first share is sent as a first-time result (US2, decisions of 2026-10-22).
- **First-time result rejected by GOB, then an amend & reshare**: the reshare is not sent to GOB either; its NOWS stay
  held (FR-008, FR-016); correction is the error-handling story (CIMD-4259).
- **Hearing over several days**: the results looked up may be those of the most recent hearing day rather than the
  day shared (see Assumptions, "Results lookup"); to be revisited if the decision changes.
- **Results not yet available** when the notification arrives: retried (US3).
- **Enforcement workflow unavailable** when the results-processing app notifies it: retried, then the app's run for that
  share is marked failed and restartable; the NOWS stay held meanwhile (FR-002a).
- **Results not available in the fast store**: taken from the Results service instead.
- **No GOB data ever received for the case and defendant** (nothing sent, or GOB never accepted): no message to the
  NOWS service; the NOWS stay held (FR-016).
- **GOB rejects the submission**, or the outcome is unknown: the NOWS stay held; the error-handling story (CIMD-4259)
  deals with them (FR-016).
- **Enforcement NOW that needs no GOB data**: still held until GOB's data is passed on; when nothing is sent to GOB it
  stays held with the others (FR-001, FR-016).
- **Enforcement case with more than one offence**: supported; each offence is sent once (FR-008).
- **Notification with request ids of NOWS that do not exist**: GOB processing still happens; GOB's data for an unknown
  request id is kept for the retention period and then released as unclaimed (FR-013). A notification without request
  ids is invalid (rejected).
- **Repeated notification** (results-processing app retry or replay) with the same request ids: no effect. A re-run that
  creates **new** request ids for already-sent offences: nothing to GOB, GOB data sent with the new request ids (US2).
- **Amend & reshare whose NOW needs GOB data that the first send did not provide** (architect scenario 4): the NOW is
  generated with the data received; the Enforcement team produces the correct NOW and updates GOB manually (their
  existing process), prompted by the email story (both out of scope). Architect scenario 3 is not valid (cannot occur).
- **Multiple results, one right and one wrong** (a result outside the curated result-code list): all results are sent
  to GOB (FR-010, decided 2026-10-22 with the GOB/Libra team).

## Requirements *(mandatory)*

### Functional Requirements

**Trigger (results-processing app → enforcement workflow)**

- **FR-001**: For every share whose NOWS include NOWS for an Enforcement case's defendant, the results-processing app
  MUST create those NOWS in the NOWS service as today, give each such NOW a unique request id, and have the NOWS service
  hold them until GOB's data for their request id arrives. This applies to **every** NOW for that defendant, including
  NOWS that need no GOB data (clarified 2026-10-19).
- **FR-002**: After creating them, the results-processing app MUST inform the enforcement workflow of the hearing, the
  hearing date of the share, whether the share is an amend & reshare (clarified 2026-10-22) and the request ids of
  those NOWS. It MUST NOT inform it for shares without NOWS for an
  Enforcement case's defendant.
- **FR-002a**: When the notification cannot be delivered (the enforcement workflow unavailable or rejecting it), the
  results-processing app MUST retry it as it retries creating NOWS today; on final failure its processing of that share
  MUST be marked failed, visible to support and restartable, and MUST NOT be reported as successful.
- **FR-003**: The enforcement workflow's notification operation MUST be reachable only from the platform's internal
  network, in the same way the results-processing app calls Common Platform contexts today (a system user identity on
  each call); it MUST NOT be exposed through the external API gateway or be reachable from outside the platform
  (clarified 2026-10-19). A notification without a system user identity MUST be rejected.
- **FR-004**: The enforcement workflow MUST acknowledge a valid notification immediately and process it afterwards, so
  that a slow or unavailable downstream step never makes the results-processing app wait or give up. An invalid
  notification MUST be rejected with the reason.
- **FR-005**: The enforcement workflow MUST NOT react to the hearing-resulted public event any more; its listener for
  that event MUST be removed in this feature, and from the release onwards the notification is its only trigger. The
  enforcement workflow and the results-processing app changes MUST therefore be released together in each environment.

**Results**

- **FR-006**: The enforcement workflow MUST obtain the shared hearing results itself: from the platform's existing
  short-lived store of shared results when they are there, otherwise from the Results service.
- **FR-007**: Selection of the Enforcement case and defendant, and the building of the GOB submission, MUST be as
  defined by feature 001 (FR-002, FR-004, FR-006 to FR-011, FR-015) and feature 002 (data items requested).

**Sending to GOB once**

- **FR-008**: Each offence's results MUST be sent to GOB at most once, across all shares and hearings of the case,
  and only from a first-time share: an amend & reshare MUST NOT be sent to GOB, even when the first-time result of the
  offence never reached GOB (decided 2026-10-22). The enforcement workflow MUST use the reshare indicator from the
  notification for this (FR-002) and its own record of offences already sent for the "at most once". Enforcement
  cases with more than one offence MUST be supported, even though today an enforcement case has one offence. An
  offence adjourned in a share MUST NOT be sent in that share (feature 002 FR-018).
- **FR-009**: For an amend & reshare, and for a first-time share whose offences were all sent already, GOB MUST NOT be
  contacted; the enforcement workflow MUST send the most recent GOB data it received for the case and defendant to the
  NOWS service with the share's request ids, so that the share's NOWS are generated (an email on amend & reshare is its
  own story). With no earlier GOB data, unless a first-time share of the same hearing is still being processed
  (received, processing or stored failure), in which case the reshare's processing is retried and stored for replay like
  any other failure (FR-015); otherwise nothing MUST be sent to the NOWS service and the outcome MUST be recorded; the
  NOWS stay held (FR-016).
- **FR-010**: Every result of a first-time share for the Enforcement case MUST be sent to GOB with its result code
  (renamed to GOB's code where feature 001 defines a rename), irrespective of the curated list of codes GOB accepted
  until now (decided 2026-10-22, agreed with the GOB/Libra team; replaces feature 001 FR-009's dropping). Only a result
  with no code (unknown in reference data), or whose code is longer than the contract's 10 characters (never
  truncated), MUST be left out and recorded. When no result of the share has a code, GOB
  MUST NOT be contacted, nothing MUST be sent to the NOWS service and the outcome MUST be recorded.
- **FR-010a**: *Withdrawn 2026-10-22* (superseded by FR-010: all results are sent).
- **FR-011**: A repeated notification (same hearing, hearing date, reshare indicator and request ids) MUST have no
  effect. A notification only some of whose request ids belong to an earlier notification, or whose request ids belong
  to an earlier notification with another hearing, hearing date or reshare indicator, MUST be rejected as inconsistent,
  with nothing stored.

**GOB data to the NOWS service (public event)**

- **FR-012**: After GOB accepts a submission, the enforcement workflow MUST send GOB's NOWS data items exactly as GOB
  returned them (no field added, removed, renamed or reformatted), with the GOB account number and the request ids from
  the notification, to the NOWS service as a new public event on the platform's messaging system (not a direct call).
- **FR-013**: The NOWS service MUST find the held NOWS by request id, place GOB's data unchanged into each NOW's
  document data (as feature 002 FR-009) and resume their generation. Each NOW MUST be completed exactly once, also when
  the same event is delivered more than once. When a request id matches no held NOW (the event arrived first), the NOWS
  service MUST keep GOB's data for that request id for a configurable retention period and complete a NOW with that
  request id at once if it arrives within it; after the period the data MUST be released and recorded as unclaimed (as
  feature 002 FR-011). Such an event MUST NOT fail other events.
- **FR-014**: The NOWS service MUST accept this event only from the enforcement workflow's agreed event name and MUST
  ignore it, with a record, when the enforcement NOWS feature is switched off.

**Failures and replay**

- **FR-015**: No notification or event MUST be lost. Each stage MUST be retried 3 times with back-off; if it still
  fails, it MUST be stored with the stage, reason and everything needed to replay it, visible to support. Stage 1 (get
  the results, build and send the GOB submission, record the outcome) and stage 2 (send GOB's data to the NOWS service)
  MUST each be recoverable on their own; replaying stage 2 MUST NOT contact GOB. A stored failure MUST be replayed
  automatically on a longer, configurable schedule until a configurable limit is reached, and support MUST be able to
  replay it manually at any time; a failure past the limit stays stored and visible. The recorded states MUST distinguish
  sent from errored; a crash after sending but before recording is an accepted residual risk and MUST leave the outcome
  recorded as unknown.
- **FR-016**: Held NOWS MUST wait without a time limit when no GOB data is sent for them (also when the NOW reached the
  NOWS service before the enforcement workflow processed the share; feature 002's 24-hour maximum wait is removed,
  confirmed 2026-10-22), because an amend & reshare may come at any later time. This includes when GOB rejects the
  submission, fails, or the outcome is unknown: the held NOWS stay held and the workflow's recorded outcome makes the
  case visible to support (clarified 2026-10-19). Making such NOWS fail visibly, or completing them later, is left to
  the error-handling story (CIMD-4259).

**Results-processing app**

- **FR-017**: For an Enforcement case's defendant, GOB MUST be updated by the enforcement flow only; the
  results-processing app MUST NOT send its own financial-imposition update for that defendant, so a result never
  reaches GOB twice.
- **FR-018**: Shares and NOWS for non-Enforcement cases MUST be processed by the results-processing app and the NOWS
  service exactly as today.

**NOW distribution**

- **FR-021**: Whether NOWS are distributed or suppressed MUST be decided by the existing NOW subscription rules only;
  this feature MUST NOT add any manual suppression. For an amend & reshare the first-time result decides: NOWS suppressed
  for the first-time result stay suppressed, NOWS distributed the first time are distributed again when the amend &
  reshare regenerates them (the results-processing app regenerates only NOWS with an amended result) (with the reused
  GOB data, FR-009); without first-time GOB data the reshare's enforcement NOWS stay held (FR-016) (decided 2026-10-22).

**Both / data protection**

- **FR-019**: No component MUST write defendant personal data or GOB account data to application logs; logs carry
  identifiers, request ids, statuses and outcomes only (feature 001 FR-017, feature 002 FR-014).
  *Temporary exception (2026-10-23, test environments with synthetic data only):* detailed QA payload logs in W and H
  (research I19); removed before merging to main (T090).
- **FR-020**: Before production use, the carrying of GOB account data on the platform's shared public messaging topic
  MUST be covered by a recorded data-protection decision, as for GOB data kept by the NOWS service (feature 002 FR-015).

### Superseded requirements of features 001 and 002

| Earlier requirement | Replaced by |
|---|---|
| 001 FR-001 (react to every hearing-results share event) | FR-002, FR-005 |
| 001 FR-003 (never send a reshare to GOB); 002 FR-016 (amend & reshare never sent) | Kept, now decided from the notification's reshare indicator: FR-002, FR-008, FR-009 |
| 001 FR-009 (drop result codes GOB does not list) | FR-010 (all results sent) |
| 001 FR-005 (one submission per hearing, case and defendant) | FR-008, FR-011 |
| 002 FR-001 to FR-005 (direct hand-over call and its acceptance) | FR-012 to FR-015 |
| 002 FR-004 (replay out of scope) | FR-015 |
| 002 FR-006, FR-008, FR-010, FR-021 (matching by share correlation id from hearing day / order date) | FR-001, FR-013 (matching by request id) |
| 002 FR-011, FR-012 (maximum wait, then "no GOB data") | FR-016 (retention of kept data stays, FR-013) |
| 002 FR-017 (amended NOW generated only when covered by GOB data) | FR-009 (generated; email story handles differences) |
| 002 FR-013 (existing NOWS flow unchanged) | FR-001, FR-002, FR-017, FR-018 |
| 002 FR-020 (share with no result code reuses the earlier GOB data) | FR-010 (nothing sent to GOB or to the NOWS service) |

### Key Entities

- **Notification**: from the results-processing app to the enforcement workflow for one share: hearing, hearing date,
  whether it is an amend & reshare, the request ids of the enforcement NOWS created. Outcome recorded.
- **Request id**: created by the results-processing app per enforcement NOW request; carried unchanged through the
  notification, the GOB processing record and the public event; the NOWS service's matching key.
- **Sent offence** (feature 002): the record that an offence's results were sent to GOB, in which submission; prevents
  any second sending (the "at most once").
- **Submission record** (feature 001): what was sent to GOB, GOB's reply and outcome, now linked to the notification
  and its request ids.
- **GOB data event**: the public event carrying GOB's NOWS data items unchanged, the account number and the request
  ids; outcome of sending recorded.
- **Stored failure**: a stage that failed after its retries, with the reason and what is needed to replay it.
- **Held enforcement NOW** (NOWS service): a NOW created with a request id, waiting for GOB's data → completed and
  generated as today, or stays held without a time limit when no GOB data is passed on for it (FR-016).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of enforcement NOWS for which GOB accepted the results are generated with GOB's data exactly as
  returned (0 altered or invented values, 0 with another defendant's data).
- **SC-002**: 0 offences have their results sent to GOB more than once, and 0 amend & reshares are sent to GOB,
  including when a reshare arrives before its first share or a notification is repeated.
- **SC-003**: 0 notifications or GOB data events are lost: every notification received ends in a recorded final outcome
  or a stored failure that can be replayed.
- **SC-004**: Replaying a failed hand-over never causes a GOB submission (0 extra submissions).
- **SC-005**: Shares and NOWS for non-Enforcement cases show no change in behaviour.
- **SC-006**: Under normal conditions, an enforcement NOW is generated within 10 minutes of its share.
- **SC-007**: For any hearing or request id, support can tell what happened (received, sent to GOB or why not, GOB's
  answer, GOB data sent, stored failure) without reading message contents or personal data.

## Assumptions

- **Draft design**: the architect's document is not final; this spec follows the version of 20/10/2026 and will be
  re-checked against the final version (management time pressure, developer decision 2026-10-19).
- **"Account details" in the event** (architect's open question 3, not included in the draft): the full GOB NOWS data
  items plus the account number, because the NOWS service builds the document data from the NOWS data items (developer,
  2026-10-19).
- **Results lookup**: the architect's step 3 passes only the hearing id; the Results service then returns the most
  recent hearing day's results. Accepted for now (developer, 2026-10-19); open point, to be revisited if a day-specific
  lookup is decided. The short-lived store is looked up by hearing and hearing date first.
- **Scope**: the enforcement workflow, the results-processing app and the NOWS service are all changed in this feature
  by this team; other teams may review.
- **Enforcement case rule**: an Enforcement case is identified as in feature 001 FR-002 (prosecuting authority code,
  default `GAPGD00`); the results-processing app uses the same rule to decide which NOWS are enforcement NOWS.
- **One defendant per Enforcement case** (feature 001 FR-004): all request ids in a notification belong to that
  defendant's NOWS.
- **Result codes**: only a small, fixed set of result codes applies to an enforcement case (architect rule 4); all
  results are nevertheless sent to GOB (FR-010). The GOB contract's result-code list becomes open (agreed with the
  GOB/Libra team, 2026-10-22); the renames and the data-item mapping stay as in features 001 and 002.
- **Feature switch**: settled in planning (research R15): flag `EnforcementNows` in the results-processing app and
  the NOWS service, switched on and off together; off means no request ids, no hold and no notification for
  enforcement NOWS, so nothing is sent to GOB (the behaviour before CCT-1222).
- **Retry mechanism**: the platform's task-manager library (`hmcts/cp-task-manager`) is the architect's preferred
  mechanism; its suitability is assessed in planning.

## Out of Scope

- **Email to the Enforcement team on amend & reshare** (architect scenarios 1 to 5): an email is sent on amend &
  reshare, built as its own story (confirmed 2026-10-22). This feature only makes sure the NOWS for later shares are
  still generated and the data the email story needs is recorded.
- Manual correction of GOB and NOWS after an amend & reshare, including producing a NOW manually for architect
  scenario 4 (Enforcement team's existing process: upload to CP, update GOB).
- Changes to GOB or the NOWS document templates. The enforcement gateway's GOB contract changes only to open the
  result-code list (FR-010).
