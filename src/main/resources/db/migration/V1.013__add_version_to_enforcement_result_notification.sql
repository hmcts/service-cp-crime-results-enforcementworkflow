-- Feature 005 FR-020 fix: DOC_READY now fans out to two fully independent tasks
-- (DispatchResultNotificationEmailTask, UploadCaseMaterialTask) that write to the SAME row concurrently. Without
-- optimistic locking, a read-modify-save from one task can silently overwrite the other's concurrently-committed
-- columns (a lost update - confirmed in the integration test: a concurrently-saved materialId was lost this way).
-- Each task's existing generic RuntimeException catch already retries on any failure, so an
-- ObjectOptimisticLockingFailureException needs no new task-level handling - it is simply retried.

ALTER TABLE enforcement_result_notification ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
