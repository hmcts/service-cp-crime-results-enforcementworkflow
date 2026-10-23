-- Feature 002 (research R18, R21): one submission per share (correlation_id = UUID from hearing id + hearing day)
-- instead of one per hearing, so a later hearing day of the same hearing is sent.

ALTER TABLE hearing_result_submission ADD COLUMN correlation_id uuid;
-- Rows written before this migration (test environments only) get their own id: unique, and never equal to a real share's id.
UPDATE hearing_result_submission SET correlation_id = id;
ALTER TABLE hearing_result_submission ALTER COLUMN correlation_id SET NOT NULL;

DROP INDEX uq_hrs_hearing_case_defendant;
CREATE UNIQUE INDEX uq_hrs_correlation_case_defendant ON hearing_result_submission (correlation_id, case_id, defendant_id);
