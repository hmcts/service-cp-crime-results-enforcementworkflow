-- Feature 003 (data-model.md): one notification from the results-processing Function App per share with enforcement
-- NOWs. Ids, states and error codes only; no results, GOB data or personal data. Its id is the correlation_id of the
-- submissions and sent offences it creates (one submission per notification, case and defendant).

CREATE TABLE enforcement_notification (
    id                  uuid PRIMARY KEY NOT NULL,
    hearing_id          uuid NOT NULL,
    hearing_date        date NOT NULL,
    is_reshare          boolean NOT NULL,
    caller              varchar(36) NOT NULL,
    status              varchar(32) NOT NULL,
    failed_stage        varchar(16),
    last_error          varchar(255),
    attempts            integer NOT NULL DEFAULT 0,
    replay_count        integer NOT NULL DEFAULT 0,
    next_replay_at      timestamptz,
    master_defendant_id uuid,
    submission_id       uuid REFERENCES hearing_result_submission (id),
    gob_data_reused     boolean,
    received_at         timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);

CREATE INDEX idx_en_hearing ON enforcement_notification (hearing_id, hearing_date);
CREATE INDEX idx_en_replay_due ON enforcement_notification (next_replay_at) WHERE status = 'STORED_FAILURE';
