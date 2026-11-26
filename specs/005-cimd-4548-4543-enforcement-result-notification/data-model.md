# Data Model: Enforcement Result Notification Email (feature 005)

Decisions referenced as R* are in [research.md](research.md). Builds on this service's existing schema (features
001–004, `src/main/resources/db/migration`, next available `V1.010`); nothing already applied is edited.

## `enforcement_result_notification` (new) — `V1.010__create_enforcement_result_notification.sql`

One row per unique `relatedReferenceNumber` per triggering share/reshare event — the per-recipient audit trail
(the "Notification Record" key entity in spec.md), fanned out from one `POST /enforcement-notifications` trigger
(feature 003) which may cover several `relatedReferenceNumber`s on one hearing.

| Column | Type | Rule |
|---|---|---|
| `id` | UUID PK, app-assigned | reused end-to-end as both `sourceCorrelationId` (doc-gen command) and `notificationId` (gateway command) — **one id, not two** (R5) |
| `trigger_notification_id` | UUID NOT NULL | the `notificationId` feature 003's endpoint returned for the inbound trigger call; audit only, never used for correlation to the external systems |
| `hearing_id` | UUID NOT NULL | from the trigger notification; the Results cache/query lookup key (R3) |
| `hearing_date` | DATE NOT NULL | from the trigger notification; the other half of the Results cache key (R3) |
| `related_reference_number` | VARCHAR(64) NOT NULL | the Enforcement case reference this email is scoped to (= `prosecutionAuthorityReference`) |
| `notification_type` | VARCHAR(16) NOT NULL | `FIRST_SHARE` \| `AMEND_RESHARE` |
| `division_code` | VARCHAR(16) NOT NULL | prefix of `related_reference_number`, parsed as an integer (R6) — derived locally, not looked up or stored on the event |
| `area` | VARCHAR(16) NOT NULL | `ENFORCEMENT` \| `CONFISCATION`, computed by CP from `division_code` against the configured Confiscation set `{45,66,97,109,110,116}` — never trusted from the `enforcement_area` response itself (FR-003) |
| `team_email` | VARCHAR(255) NULL | resolved team inbox; null when `status = INBOX_NOT_FOUND` |
| `case_urn` | VARCHAR(64) NULL | needed for the delivery log line (FR-016) |
| `result_short_codes` | TEXT (JSON array, grouped per offence — schema below) NULL | the matched/changed shortCodes rendered into this email, grouped by offence — **retained after purge** (FR-018 clarification below); doubles as this row's own audit content and as the *next* share's Previous Share Snapshot |
| `payload_file_uri` | VARCHAR(2048) NULL | `result-notification/requests/{id}` in this service's one Blob container (R8) |
| `destination_file_uri` | VARCHAR(2048) NULL | `result-notification/documents/{id}.pdf` — the rendered PDF's future blob URL, computed by this service but written to only by the doc generator (R8) |
| `status` | VARCHAR(20) NOT NULL | state machine below |
| `last_error` | VARCHAR(255) NULL | `DOC_FAILED`/`EMAIL_FAILED` reason code only, never payload content |
| `purged_at` | TIMESTAMPTZ NULL | set once both blobs are deleted — only after `notification-sent` confirms delivery |
| `received_at`, `updated_at` | TIMESTAMPTZ NOT NULL | `@PrePersist`/`@PreUpdate`, same convention as `EnforcementNotificationEntity` |

### Indexes

```sql
CREATE UNIQUE INDEX uq_ern_trigger_ref
  ON enforcement_result_notification (trigger_notification_id, related_reference_number);
-- Blocks only a duplicate/retried fan-out of the SAME trigger call (FR-015, R10) — never a legitimate later
-- reshare, even on the same hearing+reference (spec.md User Story 2 Acceptance Scenario 6: three successive
-- amendments must each produce their own row/email).

CREATE INDEX idx_ern_hearing_ref_prev
  ON enforcement_result_notification (hearing_id, related_reference_number, received_at DESC)
  WHERE status = 'SENT';
-- The Previous Share Snapshot read: most recent SENT row for the same hearing+reference, LIMIT 1.
```

### State machine

**Correction (Clarification 2026-11-26): `SUPPRESSED` applies to FIRST_SHARE only.** The Session 2026-11-24
note that previously stood here (widening `SUPPRESSED` to both notification types, per an FR-011 reversal) is
wrong and has been removed: CIMD-4543's real acceptance criteria (fetched directly 2026-11-26) confirm the
amend-reshare path has no shortCode gate at all, so it can never reach `SUPPRESSED` — an amend-reshare always
proceeds to inbox resolution regardless of which shortCodes are present in the current state.

```text
REQUESTED ──(no trigger-list match, FIRST_SHARE only)──► SUPPRESSED  (final, FR-005)
          ──(no Division Code match)───────────────────────────────────────────────────────► INBOX_NOT_FOUND  (final, FR-006)
          ──(inbox resolved)──► DOC_REQUESTED ──► DOC_READY ──► EMAIL_REQUESTED ──► SENT   (final)
                                        │                              │
                                        ▼                              ▼
                                   DOC_FAILED                     EMAIL_FAILED
                                   (final; FR-017,             (final; FR-017, recoverable only by
                                    recoverable only by          manual re-trigger — R11; this is the
                                    manual re-trigger — R11)     state a notification-failed or
                                                                  notification-validation-failed
                                                                  message lands in, R1)
```

Transient vs. terminal distinction for the `enforcement_area` lookup: `INBOX_NOT_FOUND` is reached only on a
confirmed no-match (404-equivalent); a 5xx/timeout retries like every other external call in this codebase and
never produces this state on its own.

### FR-018 purge — explicit scope (Clarification 2026-11-24)

Purge applies **only** to the two Blob artifacts (`payload_file_uri`, `destination_file_uri`) — never to
`result_short_codes`, which must survive purge so the *next* amend-reshare's "original" column has something to
read once the blobs are gone. This is now stated explicitly in spec.md's FR-018 itself, not left to inference.

## `result_short_codes` — per-offence JSON schema (R7)

Grounded in this repo's actual domain model (`HearingResultedEvent`, `ResolvedResult`, `ResolvedCodes` in
`src/main/java/uk/gov/hmcts/cp/{event,service}`):

- `HearingResultedEvent.Offence(id, judicialResults, offenceTitle, offenceDetails)` — the last two are new,
  additive fields (← wire `offenceTitle`/`wording`, currently silently dropped by
  `@JsonIgnoreProperties(ignoreUnknown = true)`; R7a).
- `JudicialResultPrompt(promptReference, label, value)` — unchanged, the only prompt shape in this codebase.
- `JudicialResult` gains `resultLabel` (← wire `resultText`, new additive field; R7a).
- `ResolvedResult(offenceId, judicialResultId, judicialResultTypeId, cpShortCode, gobCode, adjournmentMarker,
  orderedDate, prompts)` already carries `offenceId` directly — grouping by offence is
  `groupingBy(ResolvedResult::offenceId)` over a list this service already produces elsewhere for the GOB
  pipeline, reused (not reimplemented) per R4's independence decision. Only `cpShortCode` is used (`gobCode` is
  GOB-specific).

```json
[
  {
    "offenceId": "a1b2c3d4-...",
    "offenceTitle": "Theft from a shop",
    "offenceDetails": "On 01/01/2031 at [location], dishonestly appropriated goods belonging to [victim]...",
    "shortCodes": [
      {
        "judicialResultId": "e5f6...",
        "shortCode": "SC",
        "resultLabel": "Committed to prison, suspended for 12 months",
        "orderedDate": "2031-03-07",
        "prompts": [
          { "promptReference": "IAMT", "label": "Instalment amount", "value": "£487.13" },
          { "promptReference": "PF", "label": "Payment frequency", "value": "fortnightly" }
        ]
      }
    ]
  }
]
```

`judicialResultId` is carried for audit traceability only — it is **not** the diff key (whether an amendment
reuses or replaces a `judicialResultId` across an amendment is unconfirmed, D6 in plan.md); `offenceId` is
assumed stable across an amendment and is the only id the Previous Share Snapshot diff depends on.

## Current-state and Previous-share lookup (R3)

Two distinct lookups feed the PDF, and only one of them can go wrong without blocking the hearing:

1. **Current state** (used for the "new" side on every share, and as the only side needed for a first-share
   email): reuse feature 003's existing cache-then-query sequence verbatim —
   `ResultsCacheClient.sharedResults(hearingId, hearingDate)` (Redis `GET INT_{hearingId}_{hearingDate}_result_`),
   falling back on a miss/disabled-cache/`RedisException` to `GET
   {RESULTS_URL}/results-query-api/query/api/rest/results/hearingDetails/internal/{hearingId}` (feature 003's
   contract). Both return the current (latest) hearing result only.
2. **Previous share** (used only for the "original" side of an AMEND_RESHARE — this path has no shortCode gate
   to satisfy first, per Clarification 2026-11-26): read the most recent `enforcement_result_notification` row for the same `(hearing_id,
   related_reference_number)` with `status = 'SENT'` (`idx_ern_hearing_ref_prev`), and use its
   `result_short_codes` — **not** the Results cache/API, which by the time an amend-reshare is processed already
   reflects the amended state.
   - No prior `SENT` row exists (the only earlier share was `SUPPRESSED` or `INBOX_NOT_FOUND`) → the "original"
     side renders as empty/none for every offence; a template-rendering detail, not a new functional requirement.

## System Doc Generator payload (`payloadFileUri`) — current + exactly one previous (R7)

`MatchResultNotificationTask` pre-merges current and previous per offence and computes a `changed` flag in Java,
not in the Docmosis template.

**FIRST_SHARE** (no previous share concept at all, not merely null):

```json
{
  "notificationType": "FIRST_SHARE",
  "hearingId": "...", "hearingDate": "2031-03-07", "caseUrn": "...", "relatedReferenceNumber": "...",
  "offences": [
    {
      "offenceId": "a1b2c3d4-...",
      "offenceTitle": "Theft from a shop",
      "offenceDetails": "On 01/01/2031 at ...",
      "current": { "shortCodes": [ { "judicialResultId": "...", "shortCode": "SC", "resultLabel": "...", "orderedDate": "...", "prompts": [] } ] }
    }
  ]
}
```

**AMEND_RESHARE** (current + previous per offence, plus a computed `changed` flag):

**FR-008 gap (plan.md D9, flagged by `/speckit-analyze`)**: `amendmentDate` is sourced from this row's own
`received_at` (already captured, no new column needed); the previous side's `orderedDate` already satisfies "the
original date of sentence" (no new field needed). **`amendmentReason` has no identified source anywhere in this
feature's research** — no such field exists in `HearingResultedEvent`, the Results API wire payload, or any
reference-data client found in this codebase. It is omitted from the payload below; the Docmosis template must
render FR-008's "amendment reason" line as blank/absent until a source is confirmed (D9) — this is an explicit,
tracked gap, not silently dropped.

```json
{
  "notificationType": "AMEND_RESHARE",
  "hearingId": "...", "hearingDate": "2031-03-07", "caseUrn": "...", "relatedReferenceNumber": "...",
  "amendmentDate": "2031-04-02",
  "offences": [
    {
      "offenceId": "a1b2c3d4-...",
      "offenceTitle": "Theft from a shop",
      "offenceDetails": "On 01/01/2031 at ...",
      "changed": true,
      "current": { "shortCodes": [ { "judicialResultId": "...", "shortCode": "SC", "resultLabel": "...", "orderedDate": "...", "prompts": [] } ] },
      "previous": { "shortCodes": [ { "judicialResultId": "...", "shortCode": "FO", "resultLabel": "...", "orderedDate": "...", "prompts": [] } ] }
    }
  ]
}
```

An offence present in `current` with no match in the previous snapshot has `previous: null`, `changed: true`
(new offence this share). **Open item (D7 in plan.md, not a blocker)**: the converse — an offence present in the
previous snapshot but absent from the current state (reverted/removed) — defaults to `current: null,
previous: {...}, changed: true`, not yet confirmed as a business rule.

## `cp-gov-uk-notify-gateway` dispatch (R1, R5) — integration, not new storage here

One `send-email-notification` ASB command per row reaching `EMAIL_REQUESTED`, on queue `ng-send-email`:

| Field | Source |
|---|---|
| `notificationId` | this row's own `id` (R5 — the same value already sent as `sourceCorrelationId` to the doc generator) |
| `templateId` | the template id for `notification_type` |
| `sendToAddress` | this row's `team_email` |
| `fileUri` | this row's `destination_file_uri` |
| ASB `ReplyTo` (required) | `enforcementworkflow-notification-result` — a queue this service owns and consumes |

This service consumes its own `ReplyTo` queue for the bare ASB `subject` values `notification-sent` /
`notification-failed` / `notification-validation-failed` (R1 — **not** `public.notificationnotify.events.*`, a
correction from an earlier draft) and transitions the matching row (by `notificationId` = this row's `id`) to
`SENT`, `EMAIL_FAILED`, or `EMAIL_FAILED` respectively.

## System Doc Generator BYO-FS request (R2, R8) — per `enforcement_result_notification` row

| Field | Value |
|---|---|
| `templateIdentifier` | the Docmosis template for `notification_type` |
| `conversionFormat` | `pdf` |
| `requestedTime` | ISO-8601, request time |
| `payloadFileUri` | `result-notification/requests/{id}` in this service's one Blob container (R8) — written before calling `generate-document` |
| `destinationFileUri` | `result-notification/documents/{id}.pdf` in the same container — this service only computes this URL, never writes to it; written back to `destination_file_uri` on `DOC_REQUESTED` |
| `originatingSource` | this service's own identifier |
| `sourceCorrelationId` | this row's own `id` (R5) |

Completion is **event-driven**, not polled: `DocGeneratorEventListener` consumes
`public.systemdocgenerator.events.document-available`/`generation-failed` on Artemis (reusing this service's
existing JMS consumer infrastructure), matched by `sourceCorrelationId`/`originatingSource`.
