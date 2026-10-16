-- Feature 003 (data-model.md): the request ids of a notification's enforcement NOWs. The primary key makes a repeated
-- notification a no-op (FR-011, constitution Principle III).

CREATE TABLE enforcement_notification_now (
    request_id          uuid PRIMARY KEY NOT NULL,
    notification_id     uuid NOT NULL REFERENCES enforcement_notification (id),
    master_defendant_id uuid NOT NULL
);

CREATE INDEX idx_enn_notification ON enforcement_notification_now (notification_id);
