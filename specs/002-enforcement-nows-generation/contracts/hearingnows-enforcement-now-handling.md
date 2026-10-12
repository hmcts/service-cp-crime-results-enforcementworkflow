# Contract: H handling of `hearingnows.add-now-document-request` for Enforcement cases (changed behaviour)

The existing command (`POST .../hearingnows/add-now-document`, sent by Common Platform's existing NOWS flow, DF) is **unchanged in shape**; its schema gains only the CIMD-4462 field `financialOrderDetails.enfNowsDataItems` (optional; DF does not send it). This document fixes the new behaviour.

## Hold condition (all must hold; otherwise existing behaviour, unchanged)

1. Feature toggle `EnforcementNows` is on (`FeatureControlGuard`).
2. The NOW would be processed straight away today (non-financial, or financial with an account number). Financial NOWs waiting for the staging-enforcement acknowledgement (`WAITING_FOR_GOB`) keep their existing path.
3. The NOW is for an Enforcement case: any `nowContent.cases[].prosecutorOUCode` equals the configured enforcement code (JNDI `hearingnows.enforcement.prosecution.authority.code`, default `GAPGD00`); DF fills it from the case's `prosecutionAuthorityOUCode`. Only when **no** case carries `prosecutorOUCode`, the command API persists the row as `SUBMITTED` and enqueues `CHECK_ENFORCEMENT_NOW_TASK`, which queries Progression `progression.query.prosecutioncase-v2` for the `cases[]` ids; a Progression failure is retried by the jobstore and never falls back to "not enforcement".
4. An Enforcement-case NOW without `masterDefendantId` is set to `FAILED` with `no master defendant id` (never generated without GOB data).

## Correlation (R4, R18)

A held NOW is keyed by (`enforcement_correlation_id`, `masterDefendantId`). `enforcement_correlation_id` is computed with the R18 rule from the NOW's `hearingId` and `nowContent.orderDate` as the hearing day (schema-required). **Interim** (research OQ-9): `orderDate` is the latest ordered date of the defendant's results, entered in the court UI, so it may differ from the hearing day; such a NOW never matches, waits and fails "no GOB data" (visible to support, never completed with another day's data).

## Flow when held

```text
row SUBMITTED (existing) → status WAITING_FOR_ENFORCEMENT_DATA (commit)
  → look up enforcement_nows_data (correlationId, masterDefendantId) with nows_data_items present
      found  → CompleteEnforcementNowTask
      absent → schedule ExpireWaitingEnforcementNowTask at now + max wait (JNDI hearingnows.enforcement.nows.max.wait, default PT24H)
```

## Completion (`CompleteEnforcementNowTask`)

- **Same type of result** (FR-017, R17): if the NOW is an amendment (`nowContent.amendmentDate` set), it is completed only if at least one of its results' `resultIdentifier` is in `covered_result_definition_ids` (accumulated for the hearing day); otherwise conditional update `WAITING_FOR_ENFORCEMENT_DATA → FAILED` with `result not sent to GOB` (the email is the notification story's). Other NOWs are not checked. The rule depends only on the NOW, so it holds in either arrival order.
- Conditional update `WAITING_FOR_ENFORCEMENT_DATA → ENFORCEMENT_DATA_ADDED` (only one caller wins; the loser does nothing).
- Payload: `nowContent.financialOrderDetails.enfNowsDataItems` = kept `nows_data_items` (verbatim); `accountPaymentReference` = kept `account_number`; when `financialOrderDetails` is absent, create it and set `totalBalance` / `totalAmountImposed` from GOB's `accountBalance` / `offences.accountTotal` when present (interim rule, OQ-1).
- Mark kept data `CLAIMED` (first time); enqueue the existing `PROCESS_NOW_DOCUMENT_REQUEST_TASK`. From here the existing chain (file service → system-doc-generator → material → notifications, Welsh template rule) applies unchanged.

## Expiry and release

- `ExpireWaitingEnforcementNowTask`: if still `WAITING_FOR_ENFORCEMENT_DATA`, set `FAILED` with error message `no GOB data` (FR-011, FR-012).
- `ReleaseEnforcementNowsDataTask`: scheduled whenever data is kept or replaced; acts only once the row's `release_at` has passed; nulls the GOB data and account number; status `RELEASED`, or `UNCLAIMED` if never claimed (logged at INFO, not an error).

## Queries

The existing query API (`/nows/hearing/{hearingId}`, `/nows/material/{materialId}`) shows the new statuses and `error_message` for support (SC-006).
