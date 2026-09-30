# Data Model: CIMD-4246 — Hearing Resulted to GOB (Libra)

**Feature**: `001-cimd-4246-hearing-resulted-to-libra` | **Plan**: [plan.md](plan.md) | **Decisions**: [research.md](research.md)

## 1. Inbound event projection (workflow `event/HearingResultedEvent.java`)

Java records with `@JsonIgnoreProperties(ignoreUnknown = true)` (gateway `ConfirmedHearingEvent` pattern). Only these fields are read. The wire contract is in [contracts/hearing-resulted-event.md](contracts/hearing-resulted-event.md).

```text
HearingResultedEvent
├── isReshare: boolean                     (required on event)
├── sharedTime: Instant                    (required)
├── hearingDay: LocalDate                  (required)
└── hearing
    ├── id: UUID
    ├── courtCentre { code }
    ├── defendantJudicialResults[] { masterDefendantId, defendantId, judicialResult }
    ├── courtApplications[] { courtApplicationCases[] { prosecutionCaseId } }
    └── prosecutionCases[]
        ├── id: UUID
        ├── prosecutionCaseIdentifier { caseURN, prosecutionAuthorityOUCode }
        └── defendants[]
            ├── id, masterDefendantId: UUID
            ├── prosecutionAuthorityReference        ← prosecutorDefendantId
            ├── personDefendant { personDetails { firstName, lastName, dateOfBirth,
            │       nationalInsuranceNumber, address{address1..5, postcode},
            │       contact{home, work, mobile} } }
            ├── legalEntityDefendant { organisation { name, address, contact } }   (iteration 2)
            ├── defendantCaseJudicialResults[] (JudicialResult)
            └── offences[] { id, judicialResults[] (JudicialResult) }

JudicialResult { judicialResultId, judicialResultTypeId (UUID, required), label, orderedDate,
                 judicialResultPrompts[] { promptReference, label, value } }   (prompts: iteration 2+)
```

## 2. Outbound: `HearingResultedRequest` / `HearingResultedResponse`

These are **generated** classes from `uk.gov.hmcts.cp.openapi.model` in the `api-cp-crime-results-enforcementgateway` jar. They are not hand-written. See [contracts/gateway-post-hearing-resulted.md](contracts/gateway-post-hearing-resulted.md) and the Libra YAML for the full field list. Decision ids (R#) refer to [research.md](research.md).

Iteration-1 field mapping:

| Request path | Source / rule | Story |
|---|---|---|
| `caseUrn` | `prosecutionCaseIdentifier.caseURN`, unchanged; `len ≤ 36` else mapping failure | 4246/4247 |
| `dateOfHearing` | event `hearingDay`; missing → mapping failure `DATE_OF_HEARING_MISSING` | 4247 |
| `courtHearingLocation` | `hearing.courtCentre.code` (≤ 7); missing or longer → mapping failure `COURT_HEARING_LOCATION_INVALID` | 4247 |
| `defendantDetails.prosecutorDefendantId` | defendant `prosecutionAuthorityReference`, unchanged; required, ≤ 36 | 4246 (gap 4) |
| `defendantDetails.forename` / `surname` | `personDetails.firstName` / `lastName`, truncate to 35 (4247 rule) | 4247 |
| `defendantDetails.dateOfBirth` / `nationalInsuranceNumber` | `personDetails` (NINO ≤ 9) | 4247 |
| `defendantDetails.address1..5`, `postcode` | `personDetails.address`, truncate to 35 / 8. `address1` required: if missing, mapping failure. | 4247 |
| `defendantDetails.home/work/mobileTelephoneNumber` | `personDetails.contact` (≤ 35; a longer value is left out, never truncated) | 4247 |
| `paymentTerms.paymentCardRequested` / `parentToPay` | `"N"` | 4251 default |
| `paymentTerms.paymentDueDate` | **Placeholder strategy (R13)** | 4251 (open) |
| `enforcement.prisonSentenceIndicator` | `"N"` | 4253 (iteration 2) |
| `results[].resultCode` | R4 → R5 → R6 | 4255 |
| `nowsDataRequest.nowsDataItems[].name` | R7 | 4246 |

Truncation is applied **only** to descriptive fields where a story says so (names and addresses). Identifiers are never truncated (constitution Principle VI).

## 3. Persistence: `hearing_result_submission` (Flyway `V1.001__create_hearing_result_submission.sql`)

```sql
CREATE TABLE hearing_result_submission (
    id                uuid PRIMARY KEY NOT NULL,
    hearing_id        uuid NOT NULL,
    case_id           uuid NOT NULL,
    defendant_id      uuid NOT NULL,
    case_urn          varchar(36) NOT NULL,
    shared_time       timestamp with time zone NOT NULL,
    status            varchar(32) NOT NULL,
    request_payload   jsonb,
    response_payload  jsonb,
    http_status       integer,
    error_detail      text,
    created_at        timestamp with time zone NOT NULL,
    updated_at        timestamp with time zone NOT NULL
);
-- Idempotency (gap 11): one submission per first share of a hearing/case/defendant.
CREATE UNIQUE INDEX uq_hrs_hearing_case_defendant ON hearing_result_submission (hearing_id, case_id, defendant_id);
-- Correlation lookups (gap 5).
CREATE INDEX idx_hrs_case_urn ON hearing_result_submission (case_urn);
-- Stale SENDING sweep (research.md R19).
CREATE INDEX idx_hrs_status_updated_at ON hearing_result_submission (status, updated_at);
```

**Status transitions (iteration 1):**

```text
(new) ──insert──► SENDING ──2xx──► SUCCEEDED
                         ├─non-2xx / downstream timeout (502) / transport──► FAILED  (http_status set)
                         ├─workflow-side read timeout──► FAILED  (error_detail "TIMEOUT – outcome at GOB unknown"; never resent, R20)
                         └─stale > threshold (redelivery or sweep)──► FAILED  (error_detail "INTERRUPTED – outcome at GOB unknown"; never resent, R19)
(new) ──mapping failure──► MAPPING_FAILED   (request_payload null, error_detail set)
(new) ──reference data unavailable──► FAILED   ("NOT_SENT – reference data unavailable (…)"; request_payload null, R24)
(new) ──no valid resultCode──► SKIPPED_NO_RESULT_CODE
```

- **Mapping to spec FR-013 outcomes:** accepted = `SUCCEEDED`. Rejected, failed, timed out and interrupted = `FAILED` (with `http_status` where known and `error_detail`). Unsendable = `MAPPING_FAILED`. No GOB code = `SKIPPED_NO_RESULT_CODE`. `SENDING` is transient and becomes `FAILED` if it goes stale (R19). A `FAILED` `error_detail` starts `NOT_SENT –` when nothing reached GOB, or with `TIMEOUT –` / `INTERRUPTED –` / `ERROR –` when the outcome at GOB is unknown. Otherwise it holds the gateway/Libra codes (`error=…; libraStatus=…; errorCode=…`, never Libra's free text; R24). `MAPPING_FAILED` reasons: `CASE_URN_INVALID`, `PROSECUTOR_DEFENDANT_ID_MISSING`, `ADDRESS1_MISSING`, `DATE_OF_HEARING_MISSING`, `COURT_HEARING_LOCATION_INVALID`, `PAYMENT_DUE_DATE_UNAVAILABLE`, `ORGANISATION_DEFENDANT_NOT_SUPPORTED_YET`.
- "Already submitted" means a final status (`SUCCEEDED`, `FAILED`, `MAPPING_FAILED`, `SKIPPED_NO_RESULT_CODE`) **or** a `SENDING` row younger than the stale threshold. A stale `SENDING` row found on redelivery is set to `FAILED` (INTERRUPTED) and the message is acknowledged without a POST.
- A reshare and the out-of-scope shapes are **not persisted** in iteration 1. They are only logged.
- `response_payload` is kept for the later system-doc-generator step (gap #16).
- There are no `expires_at` or purge columns yet (open item, gap #10).

JPA: `HearingResultSubmissionEntity` stores the `jsonb` columns as `String` with `@JdbcTypeCode(SqlTypes.JSON)`. Repository: `HearingResultSubmissionRepository extends JpaRepository<…, UUID>` with `findByHearingIdAndCaseIdAndDefendantId(...)` (the unique key's lookup).

## 4. Configuration model (workflow `application.yaml`, new keys)

```yaml
spring:
  datasource:
    url: ${DATASOURCE_URL:jdbc:postgresql://localhost:5432/enforcementworkflowdb}
    username: ${DATASOURCE_USERNAME:postgres}
    password: ${DATASOURCE_PASSWORD:postgres}
    hikari.maximum-pool-size: ${DATASOURCE_MAX_POOL:5}
  flyway: { enabled: true, locations: classpath:db/migration, baseline-on-migrate: true }
  jpa: { open-in-view: false, hibernate.ddl-auto: validate }
cp:
  messaging:
    hearing-resulted-subscription-name: service-cp-crime-results-enforcementworkflow.hearing-resulted
    hearing-resulted-selector: "CPPNAME = 'public.events.hearing.hearing-resulted'"
  enforcement:
    authority-code: ${ENFORCEMENT_AUTHORITY_CODE:GAPGD00}
  reference-data:
    base-url: ${REFERENCE_DATA_URL:http://localhost:8081}
    cjscppuid: ${REFERENCE_DATA_CJSCPPUID:<placeholder system-user uuid>}
    connect-timeout-ms: 5000                   # R20
    read-timeout-ms: 10000                     # R20
  enforcement-gateway:
    base-url: ${ENFORCEMENT_GATEWAY_URL:http://localhost:8082}
    connect-timeout-ms: 5000                   # R20
    read-timeout-ms: 50000                     # R20 - must exceed the gateway's total to APIM (5s + 40s)
  hearing-result:
    payment-due-date-fallback: ${PAYMENT_DUE_DATE_FALLBACK:NONE}   # R13 - default NONE; HEARING_DATE only via explicit local/test/simulator config
    result-code-renames: { TFOUT: TFOOUT, WC: DW, WWDN: WDN }
    nows-data-items-by-short-code: {}     # R7 - rows from the updated Confluence page (YAML names)
    stale-sending-threshold: ${STALE_SENDING_THRESHOLD:PT5M}           # R19 - must exceed the total call budget
    stale-sending-sweep-interval: ${STALE_SENDING_SWEEP_INTERVAL:PT1M} # R19 - worst case to a final outcome = threshold + interval, about 6 min
```
