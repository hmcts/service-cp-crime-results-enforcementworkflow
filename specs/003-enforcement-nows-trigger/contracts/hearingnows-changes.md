# Contract: hearing NOWS service (`cpp-context-hearing-nows`) changes

Research R13, R14. Base: feature 002 code on `dev/CIMD-3063`. H conventions apply (constitution: CPP framework,
Maven, JSON schema, Liquibase, jobstore, `*IT` via `runIntegrationTests.sh`).

| Area | Change |
|---|---|
| Hold | Enforcement NOWs arrive with `requestId` and no account number → existing `WAITING_FOR_GOB` routing (no code change). Feature 002's `EnforcementNowsHold` (Progression case lookup, `WAITING_FOR_ENFORCEMENT_DATA`) is removed. |
| Kept-data check on arrival | `AddNowDocumentRequestCommandApi` keeps its existing `updateStatusByMaterialId(materialId, WAITING_FOR_GOB)` line and then calls the new `AddNowDocumentRequestService.completeIfGobDataAlreadyReceived`: a NOW whose `requestId` has `KEPT` data in `enforcement_nows_data` is completed at once (schedule the completion task); otherwise nothing happens (R26). |
| New event | `subscriptions-descriptor.yaml`: `public.enforcementworkflow.enforcement-nows-data-ready` with `schema_uri` = the schema `id`; schema file in `hearingnows-event-processor/src/yaml/json/schema/` (plus the referenced `nows-data-items.json` there). |
| New processor | `EnforcementNowsDataEventProcessor` (`@ServiceComponent(EVENT_PROCESSOR)`, `@Handles(...)` on `processEnforcementNowsDataReady`, named like H's other handlers `processAcknowledgement` / `processDocumentAvailable`), no feature toggle (research R25), behaviour as [gob-data-public-event.md](gob-data-public-event.md). `userId` from `_metadata.context.user`, else the context system user. |
| Completion | `CompleteEnforcementNowTask` re-keyed by `requestId` (+ master defendant check); the coverage check (`EnforcementNowCoverage`) is removed; the merger and the "required totals" check are kept. |
| Release | `ReleaseEnforcementNowsDataTask` kept, re-keyed by `requestId`; it releases only claimed data (`RELEASED`), scheduled by `CompleteEnforcementNowTask` at the first claim + retention. Data no NOW claimed is kept without a time limit; the event processor schedules no release (R30). |
| Removed | REST command `add-enforcement-nows-data` (RAML resource, schema + example, DRL, `RuleConstants.addEnforcementNowsDataGroups`, `AddEnforcementNowsDataCommandApi` and its 3 tests, `ApiRestEndpoint.ENFORCEMENT_NOWS_DATA`), `ExpireWaitingEnforcementNowTask`, `ShareCorrelationId`, `NowShareCorrelationResolver`, status `WAITING_FOR_ENFORCEMENT_DATA` (no longer written). |
| Liquibase | `006-key-enforcement-nows-data-by-request-id.xml` (data-model.md); `004-create-enforcement-nows-data.xml`: `release_at` NULLable (R30, changed in place as 004 is not released yet). 005 unchanged. |
| Unchanged | Non-enforcement NOWs; financial NOWs completed by the staging-enforcement acknowledgement; generation, storage, distribution, Welsh rule. |
| Tests | Unit tests for the processor, service changes and tasks; `EnforcementNowsIT` rewritten to publish the public event (framework `newPublicJmsMessageProducerClientProvider`, `NowHelper` pattern) for: NOW first then event; event first then NOW (kept data); repeated event; toggle off; unknown request id released after retention; non-enforcement and financial NOWs unchanged. |
