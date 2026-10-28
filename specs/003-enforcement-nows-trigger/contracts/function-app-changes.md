# Contract: results-processing app (Function App) changes

Repo: `cpp-context-azure-legalaidagency/azure-functions/durable-functions`. Research R4–R6, R15, R25, R28.

**One NOW path (R28, tech lead review 2026-10-29; refines the isolation of R25, 2026-10-26)**: Enforcement case NOWs
are built and sent to H by the existing activities, with all other NOWs. The orchestrator `HearingResultedNowsHandler`
only adds what is enforcement specific: a request id per enforcement NOW and the call to the enforcement workflow. The
compliance flow (compliance NOWs such as FCOMP, the GOB update) is unchanged for Enforcement cases (R29). There is no feature flag. A hearing without an Enforcement case runs
exactly as before: same activities, same order, same inputs.

| Area | Change |
|---|---|
| Enforcement case rule | `CommonUtility/EnforcementCase.js` (new): a case is an Enforcement case when its prosecuting authority OU code equals env `ENFORCEMENT_AUTHORITY_CODE` (default `GAPGD00`); same rule as W (001 FR-002). `partitionByEnforcementCase(hearing, items)` splits NOW requests or compliance entries by their `masterDefendantId` (pure, order kept). |
| Orchestrator `HearingResultedNowsHandler` | `OutboundNowsVariants` builds all NOW requests as before (same reshare selection: on a reshare only amended NOWs are regenerated; same NOW content). Before `ProcessOutboundNowVariants`, every NOW request of an Enforcement case defendant without a request id gets its own `requestId = context.df.newGuid(instanceId)` (one per NOW, FR-001; deterministic across replays). A NOW request that already has one is a compliance NOW (the mapper set `complianceCorrelationId`, e.g. FCOMP) and is left unchanged (R29). `ProcessOutboundNowVariants` then sends all NOW requests to H unchanged, so H holds the enforcement NOWs in `WAITING_FOR_GOB` (and the compliance NOWs as today, until the account number). Then `NotifyEnforcementWorkflow` (below) is called with the new ids only. `SetNcesHearingResults`, `SetNowVariants` and the GOB update (`OutboundComplianceEnforcement` / `ProcessOutboundComplianceEnforcement`) get every compliance entry, Enforcement case defendants included, as on main (FR-017, FR-021, R29). |
| New activity `NotifyEnforcementWorkflow` | After `ProcessOutboundNowVariants`: POST `ENFORCEMENT_WORKFLOW_API_BASE_URI` + `/enforcement-notifications` (contract: [workflow-enforcement-notification-api.md](workflow-enforcement-notification-api.md)) with header `CJSCPPUID` = the app's existing system user (setting `CJSCPPUID`, as `ResultsService`; research R2). Body `{hearingId, hearingDate, isReshare, nows:[{requestId, masterDefendantId}]}` of the enforcement NOW requests just sent (new ids only) (none built, e.g. no subscription or a reshare without amended results: no call); `hearingDate` = the orchestration's hearing day as `yyyy-MM-dd` (a date-time is converted to the Europe/London calendar day); `nows` is limited to 50 by the contract, no chunking yet (open point OP-11); `isReshare` (required boolean, gateway 0.2.0) = `hearingResultedObj.isReshare === true` (output of `HearingResultedCacheQuery`, already recorded: replay-safe), because amend & reshare results are never sent to GOB by W. Uses `postWrapperWithDefault`; rethrows on failure. |
| Failure handling | `NotifyEnforcementWorkflow` finally failing: the orchestrator runs the remaining activities, then rethrows so the instance ends `Failed` and can be restarted (FR-002a). Any other activity failing keeps the existing `Success:false` output; when `ProcessOutboundNowVariants` fails, W is not notified. |
| Existing components | Unchanged: `FeatureFlagClient`, both NOWS triggers, `SetComplianceEnforcement`, `OutboundNowsVariants` and its mappers, `ProcessOutboundNowVariants`. Removed (R28): `EnforcementCaseNowsVariants`, `ProcessEnforcementCaseNowsVariants`. |
| Logging | ids and statuses only. |
| Tests | Jest unit tests for each new module and the orchestrator (a hearing without an Enforcement case runs the same activities with the same inputs as before; Enforcement case NOWs, financial or not, get distinct ids, and H and W get the same ids); request built for W validated against the OpenAPI draft. |
| Config (cpp-aks-deploy / function app settings) | `ENFORCEMENT_WORKFLOW_API_BASE_URI`, `ENFORCEMENT_AUTHORITY_CODE` (new); the existing `CJSCPPUID` is reused and must be in W's `NOTIFICATION_ALLOWED_SYSTEM_USERS`. |

Ordering note: H already holds the NOWs (F4) before W is notified, so W's event normally finds them; when it does not,
H keeps the data (FR-013).

Deployment note: an orchestration of a hearing with an Enforcement case that is in flight while the app is deployed
replays against the new activity order (`NotifyEnforcementWorkflow` added) and may fail with a non-determinism error; such instances are restarted
(the share is processed again). Deploy outside court-resulting peaks or drain the NOWS queue first.
