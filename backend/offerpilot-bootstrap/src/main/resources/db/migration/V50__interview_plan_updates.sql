-- Preserve the target at plan creation; an update proposal never guesses a different job.
ALTER TABLE interview_learning_plans ADD COLUMN target_role text NOT NULL DEFAULT '';
ALTER TABLE interview_learning_plans ADD COLUMN target_job_id text;
ALTER TABLE interview_learning_plans ADD COLUMN applied_updates jsonb NOT NULL DEFAULT '{}';
UPDATE interview_learning_plans p SET target_role=s.role,target_job_id=s.job_id
FROM interview_sessions s WHERE s.id=p.report_id AND s.user_id=p.user_id;
ALTER TABLE interview_learning_plans ADD CONSTRAINT ck_plan_applied_updates_object
    CHECK (jsonb_typeof(applied_updates)='object');
