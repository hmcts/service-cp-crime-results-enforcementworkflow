# Data Model: Enforcement NOWS Generation with GOB Account Data

**Feature**: `002-enforcement-nows-generation` | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

## 1. W — `hearing_result_submission` (feature 001 table, extended)

New Flyway migration `V1.002__add_nows_handover_to_hearing_result_submission.sql` (feature 001's `V1.001` is not edited):

| Column | Type | Null | Meaning |
|---|---|---|---|
| `nows_handover_status` | `varchar(16)` | yes | `HANDED_OVER` (H answered 202) or `FAILED`; null = no hand-over attempted (GOB did not accept, or the row predates this feature) |
| `nows_handover_detail` | `varchar(255)` | yes | For `FAILED`: `HTTP <status>` or the cause class (e.g. `ResourceAccessException`); never payload or PII |
| `nows_handover_at` | `timestamptz` | yes | When the hand-over attempt ended |

New migrations, one change each (constitution III): `V1.003__add_share_correlation_to_hearing_result_submission.sql` (R18, R21), `V1.004__add_case_defendant_status_index_to_hearing_result_submission.sql` (R16) and `V1.005__create_gob_sent_offence.sql` (R19, R20):

| Change | Detail |
|---|---|
| `correlation_id uuid NOT NULL` | Share correlation id (R18) of the share the row is for. Existing rows backfilled with their own `id` (test data only). |
| Unique key | `uq_hrs_hearing_case_defendant (hearing_id, case_id, defendant_id)` dropped; `uq_hrs_correlation_case_defendant (correlation_id, case_id, defendant_id)` created: one row per hearing day's share, case and defendant; a later share of the same hearing gets its own row (FR-019). |
| `status` | New value `SKIPPED_NOTHING_NEW`: nothing in the share is to be sent (every offence and non-offence result already sent, or the remaining offences adjourned; FR-018, FR-020); `error_detail` lists counts only. W still hands over the most recent GOB data (`gobDataReused: true`) and records that attempt in this row's hand-over columns. The same reuse hand-over follows a later share recorded `SKIPPED_NO_RESULT_CODE` (no GOB-recognised result at all; FR-020). |

Rules:
- The hand-over columns are set by every hand-over attempt for the submission (first share, then any reshare that uses its GOB data); the latest attempt wins. A first-share hand-over happens only after `status = SUCCEEDED` with a parsable reply (FR-001, FR-012).
- The row stays write-only for the NOWS flow (FR-003); support reads it via the README runbook.
- A replay (future, CIMD-4259) has everything it needs: `request_payload`, `response_payload` (GOB's reply incl. `nowsDataItems`), ids and caseUrn.
- **Amend & reshare (FR-016, R16)**: GOB is never contacted on a reshare. W reads the `SUCCEEDED` rows for the case and defendant: `request_payload` of all of them (GOB codes sent, for the covered results) and `response_payload` of the most recent (`nowsDataItems`) to hand over again under the reshare's own correlation id; it records the attempt in that most recent row's three columns, with `nows_handover_detail` prefixed `reshare` (e.g. `reshare: HTTP 403`). No new row is created for a reshare.

## 1b. W — `gob_sent_offence` (new; `V1.005`)

Which offences (and non-offence results) have been sent to GOB, so none is sent twice (FR-018, R19, R20).

| Column | Type | Null | Meaning |
|---|---|---|---|
| `id` | uuid | no | PK |
| `case_id` | uuid | no | prosecution case |
| `defendant_id` | uuid | no | defendant |
| `offence_id` | uuid | yes | the offence whose GOB-recognised results were sent |
| `judicial_result_id` | uuid | yes | for a result not attached to an offence (R20); exactly one of `offence_id` / `judicial_result_id` is set (check constraint) |
| `submission_id` | uuid | no | FK → `hearing_result_submission.id` (the request that sent it) |
| `correlation_id` | uuid | no | share that sent it |
| `created_at` | timestamptz | no | |

Rules:
- Unique `(case_id, offence_id)` where `offence_id` is not null, and unique `(case_id, judicial_result_id)` where it is not null (partial indexes).
- Rows are inserted in the same transaction as the `SENDING` submission row; they are never deleted or updated (a sent offence stays sent whatever GOB answered, and also when the call failed before reaching GOB, e.g. `NOT_SENT` gateway unreachable: such a submission is replayed with CIMD-4259, never re-sent by a later share; feature 001: no resend).
- An offence whose results were all dropped (no GOB code) gets no row; nor does an offence adjourned in the share (FR-018: only an offence's final result is sent).

## 2. W → H — hand-over payload (`hearingnows.add-enforcement-nows-data`)

| Field | Type | Required | Source in W | Notes |
|---|---|---|---|---|
| `correlationId` | uuid | yes | R18 from `hearing.id` + `hearingDay` | correlation key part 1; H recomputes it and rejects a mismatch |
| `hearingDay` | date | yes | event `hearingDay` | input of `correlationId` (R18, interim) |
| `hearingId` | uuid | yes | event `hearing.id` | input of `correlationId`; checks/logging |
| `masterDefendantId` | uuid | yes | event defendant `masterDefendantId` | correlation key part 2 |
| `defendantId` | uuid | yes | event defendant `id` | checks/logging |
| `prosecutionCaseId` | uuid | yes | event prosecution case `id` | checks/logging |
| `caseUrn` | string ≤ 36 | yes | case `caseURN` | unchanged (constitution VI) |
| `accountNumber` | string ≤ 36 | yes | `prosecutorDefendantId` sent to GOB (GOB account number) | → `financialOrderDetails.accountPaymentReference` |
| `nowsDataItems` | object (`nows-data-items.json`) | yes | GOB reply's `nowsDataItems`, the JSON node from the raw reply, unchanged | may be `{}` |
| `sharedTime` | date-time | yes | event `sharedTime` (of the share being handed over: first share, later hearing day, or reshare) | versions the kept data within a hearing day in H (later replaces, R18) |
| `gobDataReused` | boolean | yes | `true` when W did not call GOB for this share: an amend & reshare (FR-016) or a later share with nothing new or no GOB-recognised result (FR-020) | `true`: `nowsDataItems` are the most recent GOB data already received for the case and defendant; H applies the "same type of result" check (FR-017) |
| `coveredResultDefinitionIds` | array of uuid | yes | first or later share: the `judicialResultTypeId`s of the results sent to GOB in this submission; reshare: the reshare results whose GOB code was in a request already sent for the case and defendant (R16) | H completes a reshare NOW only if one of its results is covered (FR-017, R17); may be empty |

Full contract: [contracts/hearingnows-add-enforcement-nows-data.md](contracts/hearingnows-add-enforcement-nows-data.md).

## 3. H — `enforcement_nows_data` (new; Liquibase `004-create-enforcement-nows-data.xml`)

| Column | Type | Null | Meaning |
|---|---|---|---|
| `correlation_id` | uuid | no | PK part 1 (share correlation id, R18) |
| `master_defendant_id` | uuid | no | PK part 2 (unique key = correlation key, R4/R5) |
| `hearing_id` | uuid | no | from the hand-over |
| `defendant_id` | uuid | no | from the hand-over |
| `prosecution_case_id` | uuid | no | from the hand-over |
| `case_urn` | text | no | identifier only |
| `hearing_day` | date | no | from the hand-over |
| `shared_time` | timestamptz | no | `sharedTime` of the latest hand-over applied |
| `covered_result_definition_ids` | text (JSON array) | no | accumulated over the hearing day's hand-overs (FR-017, R17) |
| `gob_data_reused` | boolean | no | from the latest hand-over (information for support) |
| `account_number` | text | yes | null after release |
| `nows_data_items` | text (JSON) | yes | GOB data verbatim; null after release |
| `status` | text | no | `KEPT` → `CLAIMED` → `RELEASED`; or `KEPT` → `UNCLAIMED` (released without any claim) |
| `user_id` | uuid | no | CJSCPPUID of the caller |
| `received_at` | timestamptz | no | |
| `claimed_at` | timestamptz | yes | first completion |
| `release_at` | timestamptz | no | `received_at` + retention (default `PT24H`) |

Rules:
- Upsert on the PK, versioned by `shared_time` (R18): no row → insert `KEPT`; incoming `sharedTime` later than the stored one (a reshare of the same hearing day) → replace the GOB data, account number and `gob_data_reused`, add the incoming covered ids to the stored ones, keep the status (`KEPT`, or `CLAIMED` once claimed), restart `release_at`; same or earlier `sharedTime` → no-op (repeated delivery, FR-010). Another hearing day has its own row.
- `RELEASE_ENFORCEMENT_NOWS_DATA_TASK` is scheduled on every hand-over, before the keep, at now + retention (the keep, the release and the completions are one transaction); it acts only if `release_at` has passed (stale runs after a replace are no-ops), nulls `account_number`/`nows_data_items` and sets `RELEASED` (or `UNCLAIMED` if never claimed); the row and its covered ids stay, so the expiry reason of an amended NOW does not depend on whether the data was released first. Claimed data is released too (R7; analysis I2, U3).
- Contains PII/GOB financial data → ADR 001 (FR-015).

## 4. H — `now_document_request` (existing table, new statuses)

`NowDocumentRequestStatus` gains:

| Status | Set when | Next |
|---|---|---|
| `WAITING_FOR_ENFORCEMENT_DATA` | An Enforcement-case NOW arrives (hold condition, plan §Key rules) and no kept data is found | `ENFORCEMENT_DATA_ADDED` (completed) or `FAILED` (by `CompleteEnforcementNowTask`: "GOB data missing totals"; by `EXPIRE_WAITING_ENFORCEMENT_NOW_TASK` at arrival + max wait: "no GOB data" or "result not sent to GOB") |
| `ENFORCEMENT_DATA_ADDED` | GOB data merged into the payload (by whichever side wins the conditional update) | existing chain: `PAYLOAD_STORED_TO_FILE_SERVICE` → `DOCUMENT_REQUESTED` → `DOCUMENT_AVAILABLE` → … |

State diagram (enforcement NOW):

```text
SUBMITTED ──(hold condition)──> WAITING_FOR_ENFORCEMENT_DATA ──(data, covered if amended)──> ENFORCEMENT_DATA_ADDED ──> existing chain
    │                                   ├──(data without the required totals)──> FAILED ("GOB data missing totals")
    │                                   └──(max wait)──> FAILED ("no GOB data" / "result not sent to GOB")
    └──(no master defendant id)──> FAILED ("no master defendant id")
Kept data already present: the NOW still passes through WAITING_FOR_ENFORCEMENT_DATA; its first completion task completes it.
Non-enforcement NOW: SUBMITTED ──> existing behaviour (unchanged)
```

`status`, `error_message` columns already exist. New columns `master_defendant_id uuid` and `enforcement_correlation_id uuid` (changeset `005-add-enforcement-columns-to-now-document-request.xml`, index on `enforcement_correlation_id, master_defendant_id, status`): `master_defendant_id` set from the request on insert; `enforcement_correlation_id` set when the NOW is held, from the NOW's `hearingId` and `nowContent.orderDate` (R4, R18 interim).

`FAILED` reasons added (`error_message`): `no GOB data` (max wait passed, FR-011/FR-012), `result not sent to GOB` (amended NOW whose results the share's GOB data never covered, at the max wait, FR-017), `GOB data missing totals` (NOW without `financialOrderDetails` and GOB data without `accountBalance` / `offences.accountTotal`, OQ-1), `no master defendant id` (Enforcement-case NOW without one, R4); a jobstore task whose retries are exhausted sets `FAILED` with `<task>: <message>` (existing pattern).

When the enforcement check needs Progression (no case carries the enforcement code and some case has no `prosecutorOUCode`, R3), the NOW stays `SUBMITTED` while `CHECK_ENFORCEMENT_NOW_TASK` runs (retried by the jobstore), then moves to `WAITING_FOR_ENFORCEMENT_DATA`, `ENFORCEMENT_DATA_ADDED` or the existing path.

## 5. H — NOW payload after completion (CIMD-4462 data model)

`nowContent.financialOrderDetails` (schema `financial-order-details.json`, PR #186):
- `enfNowsDataItems`: the hand-over's `nowsDataItems`, unchanged.
- `accountPaymentReference`: the hand-over's `accountNumber`.
- When the NOW arrived without `financialOrderDetails` (non-financial NOW): created with the two fields above, plus the schema-required `totalBalance` = `enfNowsDataItems.accountBalance` and `totalAmountImposed` = `enfNowsDataItems.offences.accountTotal`; when GOB did not return both, the NOW is `FAILED` "GOB data missing totals" instead (OQ-1 answered 2026-10-12). All other `nowContent` fields stay as DF built them. The public event and the query API return the payload without `enfNowsDataItems`; only the stored payload (the document) carries it.
