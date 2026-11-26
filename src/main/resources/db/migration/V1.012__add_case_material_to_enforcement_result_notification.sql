-- Feature 005 FR-020 (coverage gap against CIMD-4548/4543's real Jira ACs, found after initial implementation):
-- the rendered notification PDF must also attach to the case's own Case Material section in CP, not only be
-- emailed. Independent of the email-dispatch state machine (status column) per FR-010's independence principle.

ALTER TABLE enforcement_result_notification ADD COLUMN material_id UUID;
ALTER TABLE enforcement_result_notification ADD COLUMN case_material_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED';

-- ProsecutionCase.id() (= Progression's prosecutionCaseId), captured by ResolveResultNotificationTask alongside
-- caseUrn but not previously persisted - needed by UploadCaseMaterialTask without re-fetching the hearing event.
ALTER TABLE enforcement_result_notification ADD COLUMN prosecution_case_id UUID;
