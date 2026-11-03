-- Feature 003 (research R10): cp-task-manager 1.0.11's jobs table, copied verbatim from the library's
-- db/taskmanager/V1__create_jobs_table.sql. Keep in sync; do not edit independently. The library's own schema
-- management is switched off (taskmanager.schema.enabled=false): its V1/V2 would be out of order against V1.001-V1.005.

CREATE TABLE jobs (
    job_id UUID PRIMARY KEY,
    assigned_task_name TEXT,
    assigned_task_start_time TIMESTAMPTZ,
    job_data TEXT NOT NULL,
    priority INT NOT NULL DEFAULT 10,
    retry_attempts_remaining INT NOT NULL DEFAULT 0,
    worker_id UUID,
    worker_lock_time TIMESTAMPTZ
);
