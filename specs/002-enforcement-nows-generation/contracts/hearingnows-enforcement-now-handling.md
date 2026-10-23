# Contract: H handling of `hearingnows.add-now-document-request` for Enforcement cases (changed behaviour)

The existing command (`POST .../hearingnows/add-now-document`, sent by Common Platform's existing NOWS flow, DF) is **unchanged in shape**; its schema gains only the CIMD-4462 field `financialOrderDetails.enfNowsDataItems` (optional; DF does not send it). This document fixes the new behaviour.

## Hold condition (all must hold; otherwise existing behaviour, unchanged)

1. Feature toggle `EnforcementNows` is on (`FeatureControlGuard`).
2. The NOW would be processed straight away today (non-financial, or financial with an account number). Financial NOWs waiting for the staging-enforcement acknowledgement (`WAITING_FOR_GOB`) keep their existing path.
3. The NOW is for an Enforcement case: any `nowContent.cases[].prosecutorOUCode` equals the configured enforcement code (JNDI `hearingnows.enforcement.prosecution.authority.code`, default `GAPGD00`); DF fills it from the case's `prosecutionAuthorityOUCode`. When no case carries the enforcement code and **some** case carries no `prosecutorOUCode` (e.g. a case with a `prosecutor` object), the command API persists the row as `SUBMITTED` and enqueues `CHECK_ENFORCEMENT_NOW_TASK`, which queries Progression `progression.query.prosecutioncase-v2` for the `cases[]` ids (stopping at the first Enforcement case); a Progression failure, or a case Progression does not return, is retried by the jobstore (then `FAILED`) and never falls back to "not enforcement". A case Progression returns without `prosecutionAuthorityOUCode` (optional in CP) is **not** an Enforcement case: the enforcement authority is identified by that code, and W decides the same way (review 2026-10-13: otherwise ordinary NOWs for such prosecutors would fail). The hold (status change and its completion and expiry tasks) commits in one transaction; from `CHECK_ENFORCEMENT_NOW_TASK` it runs in the job's own transaction, so the task's retry handling is kept.
4. An Enforcement-case NOW without `masterDefendantId` is set to `FAILED` with `no master defendant id` (never generated without GOB data).

## Correlation (R4, R18)

A held NOW is keyed by (`enforcement_correlation_id`, `masterDefendantId`). `enforcement_correlation_id` is computed with the R18 rule from the NOW's `hearingId` and `nowContent.orderDate` as the hearing day (schema-required). **Interim** (research OQ-9): `orderDate` is the latest ordered date of the defendant's results, entered in the court UI, so it may differ from the hearing day; such a NOW never matches, waits and fails "no GOB data" (visible to support, never completed with another day's data).

## Flow when held

```text
row SUBMITTED (existing) → in one transaction:
  status WAITING_FOR_ENFORCEMENT_DATA, enforcement_correlation_id set
  schedule CompleteEnforcementNowTask now and again in 1 minute (does nothing until kept data with nows_data_items exists;
    the second run covers a hand-over committed while the hold was open)
  schedule ExpireWaitingEnforcementNowTask at now + max wait (JNDI hearingnows.enforcement.nows.max.wait, default PT24H)
a hand-over arriving later schedules CompleteEnforcementNowTask for every NOW still waiting for its key
```

## Completion (`CompleteEnforcementNowTask`)

- **Same type of result** (FR-017, R17): if the NOW is an amendment (`nowContent.amendmentDate` set), it is completed only if at least one of its results' `resultIdentifier` is in `covered_result_definition_ids` (accumulated for the hearing day); otherwise it keeps waiting: a later reshare hand-over of the same share accumulates covered ids and enqueues completion again, so the rule holds in either arrival order. At the maximum wait it fails `result not sent to GOB` (the email is the notification story's). Other NOWs are not checked. Covered ids are compared case-insensitively.
- Conditional update `WAITING_FOR_ENFORCEMENT_DATA → ENFORCEMENT_DATA_ADDED` (only one caller wins; the loser does nothing).
- Payload: `nowContent.financialOrderDetails.enfNowsDataItems` = kept `nows_data_items` (verbatim); `accountPaymentReference` = kept `account_number`; when `financialOrderDetails` is absent, create it and set `totalBalance` / `totalAmountImposed` from GOB's `accountBalance` / `offences.accountTotal`; when GOB did not return both, the NOW is not completed but `FAILED` "GOB data missing totals" (conditional update; nothing claimed; OQ-1 answered 2026-10-12: fail visibly, never invent values).
- Mark kept data `CLAIMED` (first time); enqueue the existing `PROCESS_NOW_DOCUMENT_REQUEST_TASK` with the metadata given a new message id and the NOW's own user (so NOWs completed by one hand-over never share a message id). The public `public.hearingnows.now-document-requested` event carries the NOW **without** `financialOrderDetails.enfNowsDataItems` (GOB data stays off the public topic, and subscribers' schemas such as cpp-context-subscriptions' `financial-order-details-v2` do not allow it; `accountPaymentReference` stays); the stored payload, used for the document, keeps it (review 2026-10-12). From here the existing chain (file service → system-doc-generator → material → notifications, Welsh template rule) applies unchanged.

## Expiry and release

- `ExpireWaitingEnforcementNowTask`: if still `WAITING_FOR_ENFORCEMENT_DATA`, set `FAILED` with error message `result not sent to GOB` when kept GOB data exists for its share but covers none of the amended NOW's results (FR-017), otherwise `no GOB data` (FR-011, FR-012).
- `ReleaseEnforcementNowsDataTask`: scheduled on every hand-over **before** the data is kept (so kept data always has a release; a no-op for a repeat or a failed keep); acts only once the row's `release_at` has passed; nulls the GOB data and account number; status `RELEASED`, or `UNCLAIMED` if never claimed (logged at INFO, not an error).

## Queries

The existing query API (`/nows/hearing/{hearingId}`, `/nows/material/{materialId}`) shows the new statuses and `error_message` for support (SC-006). Its `payload` leaves out `financialOrderDetails.enfNowsDataItems` (GOB's account data is for the document only and is not shown to the query API's user groups; developer decision 2026-10-13); `accountPaymentReference` stays. Payloads without GOB data are returned exactly as stored.
