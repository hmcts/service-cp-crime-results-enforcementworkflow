-- Feature 002 (research R18-R21): one submission per share (correlation_id = UUID from hearing id + hearing day)
-- instead of one per hearing, so a later hearing day of the same hearing is sent; and a record of every offence
-- (or result not attached to an offence) sent to GOB, so none is ever sent twice.

ALTER TABLE hearing_result_submission ADD COLUMN correlation_id uuid;
-- Rows written before this migration (test environments only) get their own id: unique, and never equal to a real share's id.
UPDATE hearing_result_submission SET correlation_id = id;
ALTER TABLE hearing_result_submission ALTER COLUMN correlation_id SET NOT NULL;

DROP INDEX uq_hrs_hearing_case_defendant;
CREATE UNIQUE INDEX uq_hrs_correlation_case_defendant ON hearing_result_submission (correlation_id, case_id, defendant_id);
-- Reuse of the most recent GOB data for a case and defendant (research R16).
CREATE INDEX idx_hrs_case_defendant_status ON hearing_result_submission (case_id, defendant_id, status);

CREATE TABLE gob_sent_offence (
    id                  uuid PRIMARY KEY NOT NULL,
    case_id             uuid NOT NULL,
    defendant_id        uuid NOT NULL,
    offence_id          uuid,
    judicial_result_id  uuid,
    submission_id       uuid NOT NULL REFERENCES hearing_result_submission (id),
    correlation_id      uuid NOT NULL,
    created_at          timestamp with time zone NOT NULL,
    CONSTRAINT ck_gso_offence_or_result CHECK (num_nonnulls(offence_id, judicial_result_id) = 1)
);

-- Each offence's results, and each result not attached to an offence, are sent at most once per case (FR-018).
CREATE UNIQUE INDEX uq_gso_case_offence ON gob_sent_offence (case_id, offence_id) WHERE offence_id IS NOT NULL;
CREATE UNIQUE INDEX uq_gso_case_judicial_result ON gob_sent_offence (case_id, judicial_result_id) WHERE judicial_result_id IS NOT NULL;
