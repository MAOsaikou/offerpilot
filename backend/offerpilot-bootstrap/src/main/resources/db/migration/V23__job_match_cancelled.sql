ALTER TABLE job_matches DROP CONSTRAINT IF EXISTS job_matches_status_check;
ALTER TABLE job_matches ADD CONSTRAINT job_matches_status_check
    CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'));
