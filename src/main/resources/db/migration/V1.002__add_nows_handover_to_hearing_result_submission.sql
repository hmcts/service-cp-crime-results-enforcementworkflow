-- Feature 002 (research R11): outcome of handing GOB's reply over to hearing-nows. Null = no hand-over attempted.
-- nows_handover_detail carries status codes / cause class names only, never payload or PII.
ALTER TABLE hearing_result_submission ADD COLUMN nows_handover_status varchar(16);
ALTER TABLE hearing_result_submission ADD COLUMN nows_handover_detail varchar(255);
ALTER TABLE hearing_result_submission ADD COLUMN nows_handover_at timestamp with time zone;
