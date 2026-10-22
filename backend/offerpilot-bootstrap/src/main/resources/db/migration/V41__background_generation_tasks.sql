-- One durable lifecycle for non-chat AI work. Domain reports remain their own source of truth.
CREATE TABLE generation_tasks (
    id varchar(80) PRIMARY KEY,
    user_id varchar(80) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind varchar(40) NOT NULL,
    entity_id varchar(80) NOT NULL,
    resume_id varchar(80) REFERENCES resumes(id) ON DELETE CASCADE,
    version_id varchar(80) REFERENCES resume_versions(id) ON DELETE CASCADE,
    interview_id varchar(80) REFERENCES interview_sessions(id) ON DELETE CASCADE,
    request_id varchar(80) NOT NULL,
    request_hash varchar(64) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    stage varchar(200) NOT NULL,
    title varchar(80) NOT NULL,
    href varchar(500) NOT NULL,
    result_id varchar(80),
    result jsonb,
    error varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    UNIQUE(user_id, request_id)
);
CREATE INDEX generation_tasks_owner_recent ON generation_tasks(user_id, created_at DESC, id DESC);
CREATE INDEX generation_tasks_active ON generation_tasks(status) WHERE status IN ('QUEUED','RUNNING');
