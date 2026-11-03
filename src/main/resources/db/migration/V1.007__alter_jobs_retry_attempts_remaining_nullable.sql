-- Feature 003 (research R10): cp-task-manager 1.0.11's db/taskmanager/V2__alter-column-retry_attempts_remaining_nullable.sql,
-- copied verbatim. Keep in sync; do not edit independently.

ALTER TABLE jobs
ALTER COLUMN retry_attempts_remaining DROP NOT NULL;
