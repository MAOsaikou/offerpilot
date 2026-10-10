ALTER TABLE applications
    ADD COLUMN next_action text NOT NULL DEFAULT '' CHECK (char_length(next_action) <= 1000),
    ADD COLUMN next_action_at date;
CREATE INDEX idx_applications_due ON applications(user_id, next_action_at)
    WHERE next_action_at IS NOT NULL AND status <> 'CLOSED';

CREATE TABLE application_events (
    id text PRIMARY KEY,
    application_id text NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('CREATED','STATUS_CHANGED','NOTE_EDITED','NEXT_ACTION_EDITED','ARTIFACT_LINKED')),
    from_status text CHECK (from_status IN ('TODO','APPLIED','INTERVIEW','OFFER','CLOSED')),
    to_status text CHECK (to_status IN ('TODO','APPLIED','INTERVIEW','OFFER','CLOSED')),
    detail text NOT NULL DEFAULT '',
    artifact_type text CHECK (artifact_type IN ('JOB_MATCH')),
    artifact_id text,
    actor text NOT NULL CHECK (actor IN ('USER','AGENT','SYSTEM')),
    run_id text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((kind = 'ARTIFACT_LINKED') = (artifact_id IS NOT NULL AND artifact_type IS NOT NULL))
);
CREATE INDEX idx_application_events_app ON application_events(application_id, created_at, id);
CREATE UNIQUE INDEX uq_application_events_artifact ON application_events(application_id, artifact_type, artifact_id)
    WHERE artifact_id IS NOT NULL;

-- Baseline only: historical changes before V24 cannot be reconstructed.
INSERT INTO application_events(id,application_id,kind,to_status,detail,actor,created_at)
SELECT 'evt_baseline_' || id,id,'CREATED',status,'迁移时的阶段快照（此前历史未记录）','SYSTEM',created_at FROM applications;
INSERT INTO application_events(id,application_id,kind,detail,artifact_type,artifact_id,actor,created_at)
SELECT 'evt_match_' || a.id || '_' || m.id,a.id,'ARTIFACT_LINKED','岗位匹配报告已关联','JOB_MATCH',m.id,'SYSTEM',clock_timestamp()
FROM applications a JOIN job_matches m ON m.user_id=a.user_id AND m.job_id=a.job_id
WHERE m.status='COMPLETED';
