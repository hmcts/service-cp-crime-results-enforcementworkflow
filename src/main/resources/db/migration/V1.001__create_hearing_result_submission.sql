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
