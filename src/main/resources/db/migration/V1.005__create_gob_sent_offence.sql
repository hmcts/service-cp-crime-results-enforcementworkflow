-- Feature 002 (research R19, R20): a record of every offence (or result not attached to an offence) sent to GOB, so none
-- is ever sent twice.

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
