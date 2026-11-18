# Feature Specification: Payment terms in the hearing result sent to GOB (CIMD-4251)

**Feature Branch**: `004-cimd-4251-payment-terms` (directory only; the user manages git branches, so the speckit branch hook was not run)
**Created**: 2026-11-15
**Status**: Approved (2026-11-15, user)
**Jira**: CIMD-4251 (parent epic CIMD-2862 "4. Post hearing results (outcome) back to enforcement")
**Source**: `specs/docs/CIMD-4251.doc` (12/Nov/26 export; same requirements as `specs/docs/CIMD-4251.pdf`, 25/09/26)
**Input**: User description: "CIMD-4251: Include Payment Terms in the hearingResultRequest payload sent to GOB Libra when payment data is captured in the result. Replaces 001's deferred T069 (PaymentTermsMapper). Decided 2026-11-15: paymentTerms is optional in our contract (as in Libra v0.6.0 and AC2), so with no Payment Terms result the block is omitted and no paymentDueDate placeholder is needed (resolves 001 T066 paymentDueDate ruling / research R13). Open (left for later, do not guess): the exact CP prompt references and value formats for IAMT, PF, LSA, instalmentStartDate, PBD, PAYMENT_CARD_REQUIRED, PARENT_GAURDIAN_TO_PAY, and how PF values map to W/M/F."

## Context

When an enforcement hearing is resulted, Common Platform (CP) sends a hearing result request to GOB (Libra) for the
defendant's account (feature 001, CIMD-4246). Today that request always carries a stand-in payment terms block (due
date = hearing date, payment card "N", parent to pay "N"), and a hearing that has a real payment terms result is not
sent at all, because the payment terms were never mapped. GOB therefore never learns a new payment arrangement made in
an enforcement hearing.

This feature sends the payment arrangement the court actually made, and sends nothing about payment terms when the
court made none.

**Decision recorded 2026-11-15 (resolves feature 001 T066 `paymentDueDate` ruling / research R13):** the payment terms
block is optional in the gateway contract, as in Libra's contract v0.6.0 and as CIMD-4251 AC2 requires. With no
payment terms result the block is left out, so no stand-in due date is ever needed.

### Terms

- **Enforcement parent result**: one of FTTP (further time to pay), MPSO (money payment supervision order), PGPAY
  (parent/guardian ordered to pay) and SC (postponed committal to enforce money owed).
- **Payment terms result**: the court's payment arrangement, one of INSTL (instalments only), LUMSI (lump sum plus
  instalments), PDATE (pay by date) and PTFOR (payment terms on release). Its values are captured as prompts.
- **Payment terms block**: the part of the hearing result request that tells GOB the arrangement: instalment
  frequency, first payment / due date, instalment amount, lump sum, payment card requested, parent to pay.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Instalment arrangements reach GOB (Priority: P1)

A court in an enforcement hearing orders the defendant to pay by instalments (INSTL), or by a lump sum then
instalments (LUMSI), under any enforcement parent result. When the result is shared, GOB receives the frequency, the
first payment date, the instalment amount and, for LUMSI, the lump sum, so the account matches what the court ordered.

**Why this priority**: it is the most common case (CIMD-4251 Example 2) and today these hearings are not sent to GOB
at all.

**Independent Test**: share an enforcement hearing with SC and INSTL (amount 500, weekly, first payment today + 10
days) and check the request GOB receives.

**Acceptance Scenarios** (CIMD-4251 AC1, AC3, scenarios 3.1, 3.2):

1. **Given** a hearing resulted with SC and INSTL (instalment amount 500, frequency weekly, start date today + 10
   days, payment card and parent to pay not entered), **When** the result is shared, **Then** the request is sent with
   payment terms frequency "W", due date today + 10 days, instalment amount 500, payment card "N", parent to pay "N",
   and no lump sum.
2. **Given** a hearing resulted with INSTL at frequency monthly, **When** the result is shared, **Then** the frequency
   is "M", the instalment amount and due date are sent, and no lump sum is sent.
3. **Given** a hearing resulted with LUMSI (lump sum 2500, instalment amount 500, monthly, start date), **When** the
   result is shared, **Then** everything INSTL sends is sent, plus lump sum 2500.
4. **Given** each enforcement parent (SC, MPSO, PGPAY, FTTP) in turn, resulted with a payment terms result that has
   all its mandatory and optional prompts, **When** the result is shared, **Then** the payment terms block is sent
   with all its data.
5. **Given** a successful send, **When** GOB replies with success, **Then** the submission is recorded as sent, as
   for any other submission (feature 001).

---

### User Story 2 - No payment terms result, no payment terms block (Priority: P1)

A court makes no payment arrangement (for example MPSO without a payment terms result, where one is optional). The
request sent to GOB carries no payment terms block, so GOB keeps the arrangement it already holds instead of being
given an invented one.

**Why this priority**: it stops the stand-in due date (the hearing date) from being sent as if it were a real
commitment, which could overwrite a live arrangement in GOB. It is also the smallest independent change.

**Independent Test**: share an enforcement hearing with MPSO and no payment terms result and check the request.

**Acceptance Scenarios** (CIMD-4251 AC2):

1. **Given** a hearing resulted with MPSO and no payment terms result, **When** the result is shared, **Then** the
   request has no payment terms block, still carries the MPSO result, and is sent to GOB.
2. **Given** any hearing resulted with no payment terms result (for example a warrant result only), **When** the
   result is shared, **Then** the request has no payment terms block and is sent.

---

### User Story 3 - Pay-by-date arrangements reach GOB (Priority: P2)

A court orders the defendant to pay in full by a date (PDATE), or sets payment terms on release (PTFOR). GOB
receives only the due date and the two flags.

**Why this priority**: same value as story 1 for a less common arrangement.

**Independent Test**: share an enforcement hearing with PDATE (pay by date given) and check the request.

**Acceptance Scenarios** (CIMD-4251 scenarios 3.3, 3.4):

1. **Given** a hearing resulted with PDATE and a pay-by date, **When** the result is shared, **Then** the due date is
   the pay-by date, payment card and parent to pay are sent, and frequency, instalment amount and lump sum are not
   sent.
2. **Given** a hearing resulted with PTFOR and a pay-by date, **When** the result is shared, **Then** the request has
   the same shape as for PDATE.

---

### User Story 4 - Payment card and parent-to-pay flags (Priority: P3)

When the court asks for a payment card, or orders the parent or guardian to pay, GOB is told; otherwise both flags are
sent as "N".

**Why this priority**: refines the block built by stories 1 and 3.

**Independent Test**: share a PGPAY hearing with a payment terms result where both prompts are entered as yes.

**Acceptance Scenarios** (CIMD-4251 scenarios 3.5, 3.6):

1. **Given** a payment terms result where the payment card prompt and the parent/guardian-to-pay prompt were not
   entered (the payment card prompt is not offered on PDATE or PTFOR, and PTFOR has neither), **When** the result is
   shared, **Then** payment card is "N" and parent to pay is "N".
2. **Given** a PGPAY hearing with a payment terms result where payment card required is "Y" and parent/guardian to
   pay is "Y", **When** the result is shared, **Then** payment card is "Y" and parent to pay is "Y".

---

### Edge Cases

- **A mandatory value is missing or cannot be read** (no start date or pay-by date; INSTL or LUMSI without an
  instalment amount or frequency; LUMSI without a lump sum; an amount, date or frequency that cannot be interpreted):
  the request is not sent. The submission is recorded as unsendable with a reason naming the missing or unreadable
  value (never the value itself). No value is invented or defaulted.
- **A frequency other than weekly, fortnightly or monthly**: treated as unreadable (above).
- **More than one payment terms result for the defendant in the same share**: if they give the same payment terms
  block, it is sent once; if they differ, the request is not sent and is recorded as unsendable with a "conflicting
  payment terms" reason (OQ-2).
- **Amounts given with a currency sign or separators** (for example "£1,500.00"): sent as the number (1500.00).
- **Optional prompts with no payload field** (for example DID on INSTL, LUMSI and PDATE): ignored.
- **A payment terms result on an offence that is not part of this submission** (already sent to GOB, or adjourned,
  per feature 002): not used, like any other result left out of the submission.
- **Hearings that previously failed only because payment terms were not mapped**: not re-sent automatically; this
  feature applies to shares received after it is deployed (as for other first-share failures in feature 001).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST include a payment terms block in the hearing result request when, and only when, the
  defendant's results in the submission include a payment terms result (INSTL, LUMSI, PDATE or PTFOR), whichever
  enforcement parent result (SC, MPSO, PGPAY, FTTP) it was given under.
- **FR-002**: The system MUST leave the payment terms block out entirely when the results include no payment terms
  result. The stand-in block (hearing date as due date) MUST no longer be sent in any environment.
- **FR-003**: For INSTL the block MUST contain: frequency (from the payment frequency prompt), due date (from the
  instalment start date), instalment amount (from the instalment amount prompt), payment card and parent to pay; and
  MUST NOT contain a lump sum.
- **FR-004**: For LUMSI the block MUST contain everything FR-003 requires plus the lump sum (from the lump sum amount
  prompt).
- **FR-005**: For PDATE and PTFOR the block MUST contain: due date (from the pay-by date prompt), payment card and
  parent to pay; and MUST NOT contain frequency, instalment amount or lump sum.
- **FR-006**: Frequency MUST be sent as "W" (weekly), "F" (fortnightly) or "M" (monthly), using the agreed mapping
  from the captured value (OQ-1, resolved: PF fixed list values weekly / fortnightly / monthly).
- **FR-007**: Payment card requested MUST be "Y" only when the payment card prompt was captured as yes, and "N"
  otherwise (including when the prompt is not offered).
- **FR-008**: Parent to pay MUST be "Y" only when the parent/guardian-to-pay prompt was captured as yes, and "N"
  otherwise.
  *Implementation note (FR-007, FR-008):* "otherwise" means the prompt is absent from the result (hearing also drops
  PARENT_GAURDIAN_TO_PAY from the event when it is "false"). If the prompt's reference were not configured, the
  workflow could not tell "absent" from "captured under an unknown reference", so the share would be unsendable
  (`PAYMENT_TERMS_UNREADABLE`, "not configured") rather than sending an unchecked "N".
- **FR-009**: When a block is sent, due date, payment card and parent to pay MUST always be present; instalment
  amount MUST be present whenever frequency is present.
- **FR-010**: Amounts MUST be sent as numbers with at most two decimal places, without currency signs or separators.
  Dates MUST be sent as calendar dates.
- **FR-011**: When a payment terms result is present but a value that FR-003 to FR-009 require is missing,
  unreadable or conflicting, the system MUST NOT send the request and MUST record the submission as unsendable with a
  specific reason (missing / unreadable / conflicting payment terms), following the existing unsendable handling.
  Values MUST NOT be invented, defaulted (other than the two "N" flags of FR-007/FR-008) or truncated.
- **FR-012**: The three enforcement override fields (enforcement override, enforcer, LJA code) MUST NOT be sent.
- **FR-013**: The rest of the request (case, defendant, enforcement, results, NOWS data request) MUST be unchanged by
  this feature; in particular payment terms results keep being sent in the results list as today.
- **FR-014**: Logs and failure details (`error_detail`) MUST NOT contain the payment amounts, dates or other captured
  prompt values; they may contain the payment terms result short code, the field name and the failure reason. (The
  stored request payload, which holds the whole request sent to GOB as for every other field, is out of scope;
  clarified 2026-11-18.)

### Key Entities

- **Payment terms result**: a judicial result with short code INSTL, LUMSI, PDATE or PTFOR, belonging to the defendant
  in the shared hearing, with its captured prompts (amounts, frequency, dates, yes/no flags).
- **Payment terms block**: frequency (W/F/M, optional), due date (mandatory), instalment amount (mandatory with
  frequency), lump sum (optional), payment card requested (Y/N, mandatory), parent to pay (Y/N, mandatory).
- **Submission** (existing, feature 001): the record of one hearing result request; gains new unsendable reasons for
  missing, unreadable or conflicting payment terms.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of enforcement hearings shared with a complete INSTL, LUMSI, PDATE or PTFOR result are sent to
  GOB with a payment terms block matching CIMD-4251's mapping table (today: 0%, they are not sent).
- **SC-002**: 0 requests sent to GOB carry a stand-in due date; every request either has the court's payment
  arrangement or no payment terms block.
- **SC-003**: Every one of the CIMD-4251 acceptance scenarios (AC1, AC2, AC3 with 3.1-3.6, for all four enforcement
  parents) passes in the automated scenario suite and in STE testing against the simulator.
- **SC-004**: Every hearing with an incomplete or unreadable payment terms result is recorded as unsendable with a
  reason that names the problem, and none is sent with invented values.

## Assumptions

- The workflow is triggered only for enforcement hearings, so any payment terms result in the share relates to an
  enforcement parent result. Imposition parent results (FCOMP, FCOST, FCPC, FO, FVEBD, FVS, STRO) are out of scope
  (CIMD-4251 "Out of scope").
- AEOC and ABDC (reserve terms) do not produce a payment terms block (CIMD-4251 "Out of scope"; covered elsewhere).
  For PGPAY only the PAYT path is tested in this story.
- The enforcement override fields stay out until BPO and Enforcement SMEs confirm a source (CIMD-4251 "Out of
  scope").
- Parent/guardian details (CIMD-4250) and employer details (CIMD-4249) are separate stories; CIMD-4251 Example 5 shows
  parent/guardian details only for context.
- CIMD-4251 Example 1 (BWTU, no payment scheduled) shows a payment terms block; it was written when the block was
  mandatory. AC2 and the 2026-11-15 decision take precedence: with no payment terms result, no block is sent (OQ-3).
- CIMD-4251 scenario 3.6 reads "I did not enter PARENT_GAURDIAN_TO_PAY = Y"; its expected result ("parentToPay = Y")
  shows the prompt was entered as "Y", which is how it is read here.
- Event records ("decision: SENT, payload includes paymentTerms") follow the existing submission records (the ticket
  says "event record will be based on the final implementation").
- Depends on the gateway contract release that makes the payment terms block optional
  (`api-cp-crime-results-enforcementgateway`, LOCAL AMENDMENT CIMD-4251), and on the gateway forwarding a request
  without it.

## Open Questions

Agreed with the user 2026-11-15: the spec is approved with the defaults below; OQ-2 and OQ-3 are concluded with the BA
after implementation and any resulting change is made then.

- **OQ-1 (resolved 2026-11-18 from CP reference data and the hearing context's publishing code; user: "resolve it,
  the code will be tested on STE")**: the prompt references and the value formats of
  `public.events.hearing.hearing-resulted`, set in `application.yaml` (research R3):
  - References, from the INSTL, LUMSI, PDATE and PTFOR result definitions primed into CP environments
    (`cpp-platform-priming` `criminalCourtsResultDefinitions`): instalment amount `IAMT`, payment frequency `PF`, lump
    sum `LSA`, instalment start date `instalmentStartDate` (older definitions: `ISTD`), pay-by date `PBD`, payment card
    `PAYMENT_CARD_REQUIRED`, parent/guardian to pay `PARENT_GAURDIAN_TO_PAY` (CP's spelling). PTFOR has only `PBD`;
    PDATE has no payment card prompt, so its card flag is always "N".
  - Frequency: the PF fixed list (`e555e078-5dd8-11e8-9c2d-fa7ae01bbebc`) values `weekly`, `fortnightly`, `monthly`
    map to W, F, M.
  - Amounts: hearing prefixes CURR values with `£` (`PublishResultUtil.reformatValue`); dates: hearing publishes
    `dd/MM/yyyy` (same method, converted from `yyyy-MM-dd`); `yyyy-MM-dd` and `dd MMM yyyy` are also accepted, as
    hearing itself accepts them.
  - Yes/no: `Yes`/`No` (hearing's results restructure, `TypeUtils.convertBooleanPromptValue`) and `true`/`false`
    (older path; captured events show both).
  STE testing (SC-003) confirms these against real shares.
- **OQ-2 (default chosen, confirm with BA)**: several different payment terms results for one defendant in one share
  are treated as conflicting and not sent (FR-011).
- **OQ-3 (confirm with BA)**: CIMD-4251 Example 1 should drop its payment terms block to match AC2.
- **Note (existing behaviour, 2026-11-18 review)**: a result whose type has no reference-data definition has no
  short code, so it is left out of the request (features 002/003). If that happened to an INSTL, LUMSI, PDATE or PTFOR
  result, the share would be sent without its payment terms. This is visible in the log as a dropped
  `unknown-result-definition`; worth confirming with the BA whether such a share should instead be unsendable.
- **OQ-4 (resolved 2026-11-18, user: "suppose the v0.8.0 spelling will be sent")**: Libra v0.8.0 renames the NOWS
  data field `Terms.english_instalmetPaymentPeriod` to `english_instalmentPaymentPeriod`. Renamed together in the
  contract, the gateway (test fixture), hearing-nows (`nows-data-items.json` in event-processor and command-api,
  `Terms.java`, test data) and the workflow (hearing-nows schema copy; simulator now bundles Libra v0.8.0). A reply
  with the old spelling would now be dropped by the gateway and rejected by the simulator. Still to check: whether
  any enforcement NOW document template reads `english_instalmetPaymentPeriod` (templates are not in these repos).
