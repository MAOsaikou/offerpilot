-- Explicit creation provenance only. Legacy JD/role text is never used for backfill.
ALTER TABLE resume_optimizations ADD COLUMN job_id text REFERENCES jobs(id) ON DELETE SET NULL;
ALTER TABLE interview_sessions ADD COLUMN job_id text REFERENCES jobs(id) ON DELETE SET NULL;
CREATE INDEX idx_resume_optimizations_job ON resume_optimizations(user_id,job_id);
CREATE INDEX idx_interview_sessions_job ON interview_sessions(user_id,job_id);
ALTER TABLE application_events DROP CONSTRAINT application_events_artifact_type_check;
ALTER TABLE application_events ADD CONSTRAINT application_events_artifact_type_check
    CHECK (artifact_type IN ('JOB_MATCH','RESUME_OPTIMIZATION','INTERVIEW','STAR_STORY'));
