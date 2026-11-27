-- Feature 005 (data-model.md): one row per unique relatedReferenceNumber per triggering share/reshare event
-- (CIMD-4548 first share, CIMD-4543 amend-and-reshare). id is reused end-to-end as both the doc-generator's
-- sourceCorrelationId and the gateway's notificationId (research.md R5) - no separate id column for either.

CREATE TABLE enforcement_result_notification (
    id                      uuid PRIMARY KEY NOT NULL,
    trigger_notification_id uuid NOT NULL,
    hearing_id              uuid NOT NULL,
    hearing_date            date NOT NULL,
    related_reference_number varchar(64) NOT NULL,
    notification_type       varchar(16) NOT NULL,
    division_code           varchar(16) NOT NULL,
    area                     varchar(16) NOT NULL,
    team_email               varchar(255),
    case_urn                 varchar(64),
    result_short_codes       text,
    payload_file_uri         varchar(2048),
    destination_file_uri     varchar(2048),
    status                   varchar(20) NOT NULL,
    last_error               varchar(255),
    purged_at                timestamptz,
    received_at              timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);

-- Blocks only a duplicate/retried fan-out of the SAME trigger call (FR-015, research.md R10) - never a legitimate
-- later reshare, even on the same hearing+reference (spec.md User Story 2 Acceptance Scenario 6).
CREATE UNIQUE INDEX uq_ern_trigger_ref
    ON enforcement_result_notification (trigger_notification_id, related_reference_number);

-- The Previous Share Snapshot read (research.md R3): most recent SENT row for the same hearing+reference, LIMIT 1.
CREATE INDEX idx_ern_hearing_ref_prev
    ON enforcement_result_notification (hearing_id, related_reference_number, received_at DESC)
    WHERE status = 'SENT';
