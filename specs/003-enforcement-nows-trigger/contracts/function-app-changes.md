# Contract: results-processing app (Function App) changes

Repo: `cpp-context-azure-legalaidagency/azure-functions/durable-functions`. Research R4–R6, R15, R25.

**Isolation (R25, tech lead review 2026-10-26)**: no existing component is changed. The enforcement NOWs have their own
activities, modelled on the existing ones, and the orchestrator `HearingResultedNowsHandler` routes to them. There is
no feature flag. A hearing without an Enforcement case runs exactly as before: same activities, same order, same inputs.

| Area | Change |
|---|---|
| Enforcement case rule | `CommonUtility/EnforcementCase.js` (new): a case is an Enforcement case when its prosecuting authority OU code equals env `ENFORCEMENT_AUTHORITY_CODE` (default `GAPGD00`); same rule as W (001 FR-002). `partitionByEnforcementCase(hearing, items)` splits NOW variants or compliance entries by their `masterDefendantId` (pure, order kept). |
| Orchestrator `HearingResultedNowsHandler` | After `NowsVariantsSubscriptions`, splits the NOW variants: those of Enforcement case defendants go to the new activities below, all others to `OutboundNowsVariants` / `ProcessOutboundNowVariants` as before (a pure split of recorded activity output: replay-safe). The existing variables keep their names: `nowsVariantsSubscriptions` is narrowed to the other variants before `OutboundNowsVariants`, `complianceEnforcementArray` to the other entries before the GOB update (R26). The enforcement activities run after `ProcessOutboundNowVariants`. The compliance entries of Enforcement case defendants are left out of the GOB update only (`OutboundComplianceEnforcement` / `ProcessOutboundComplianceEnforcement`; not called when nothing is left), so the app sends no staging-enforcement imposition for that defendant (FR-017); `SetNcesHearingResults` and `SetNowVariants` keep getting the full list, so the NOW vocabulary (e.g. major creditors) and the subscription rules decide distribution as before (FR-021). |
| New activity `EnforcementCaseNowsVariants` | Counterpart of `OutboundNowsVariants` for the Enforcement case NOW variants; reuses its handler and mappers unchanged (same reshare selection: on a reshare only amended NOWs are regenerated, same NOW content). The NOW requests are exactly those `OutboundNowsVariants` builds today; then every one of them gets a new `requestId` (`uuidv4()`, one per NOW), so H holds it (FR-001). No enforcement logic depends on whether a NOW is financial: an Enforcement case NOW is treated the same either way; only the existing mapper decides the NOW content as today (R27). Output: the NOW request array. |
| New activity `ProcessEnforcementCaseNowsVariants` | Counterpart of `ProcessOutboundNowVariants`; reuses its sending unchanged (same endpoint choice by the hearing NOWS toggle, headers, retries). Input `{cjscppuid, enforcementCaseNowsVariants}`. Rethrows on failure. |
| New activity `NotifyEnforcementWorkflow` | After `ProcessEnforcementCaseNowsVariants`: POST `ENFORCEMENT_WORKFLOW_API_BASE_URI` + `/enforcement-notifications` (contract: [workflow-enforcement-notification-api.md](workflow-enforcement-notification-api.md)) with header `CJSCPPUID` = the app's existing system user (setting `CJSCPPUID`, as `ResultsService`; research R2). Body `{hearingId, hearingDate, isReshare, nows:[{requestId, masterDefendantId}]}` of the NOW requests just created (none built, e.g. a reshare without amended results: no call); `hearingDate` = the orchestration's hearing day as `yyyy-MM-dd` (a date-time is converted to the Europe/London calendar day); `nows` is limited to 50 by the contract, no chunking yet (open point OP-11); `isReshare` (required boolean, gateway 0.2.0) = `hearingResultedObj.isReshare === true` (output of `HearingResultedCacheQuery`, already recorded: replay-safe), because amend & reshare results are never sent to GOB by W. Uses `postWrapperWithDefault`; rethrows on failure. |
| Failure handling | `NotifyEnforcementWorkflow` finally failing: the orchestrator runs the remaining activities, then rethrows so the instance ends `Failed` and can be restarted (FR-002a). Any other activity failing (including the two new ones) keeps the existing `Success:false` output. |
| Existing components | Unchanged: `FeatureFlagClient`, both NOWS triggers, `SetComplianceEnforcement`, `OutboundNowsVariants` and its mappers, `ProcessOutboundNowVariants`. |
| Logging | ids and statuses only. |
| Tests | Jest unit tests for each new module and the orchestrator (a hearing without an Enforcement case runs the same activities with the same inputs as before); request built for W validated against the OpenAPI draft. |
| Config (cpp-aks-deploy / function app settings) | `ENFORCEMENT_WORKFLOW_API_BASE_URI`, `ENFORCEMENT_AUTHORITY_CODE` (new); the existing `CJSCPPUID` is reused and must be in W's `NOTIFICATION_ALLOWED_SYSTEM_USERS`. |

Ordering note: H already holds the NOWs (F4) before W is notified, so W's event normally finds them; when it does not,
H keeps the data (FR-013).

Deployment note: an orchestration of a hearing with an Enforcement case that is in flight while the app is deployed
replays against the new activity order and may fail with a non-determinism error; such instances are restarted
(the share is processed again). Deploy outside court-resulting peaks or drain the NOWS queue first.
