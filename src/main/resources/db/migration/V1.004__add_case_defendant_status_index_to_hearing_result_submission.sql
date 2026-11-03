-- Feature 002 (research R16): reuse of the most recent GOB data accepted for a case and defendant.

CREATE INDEX idx_hrs_case_defendant_status ON hearing_result_submission (case_id, defendant_id, status);
