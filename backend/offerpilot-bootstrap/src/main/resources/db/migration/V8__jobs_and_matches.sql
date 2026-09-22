CREATE TABLE jobs (
    id text PRIMARY KEY,
    site text NOT NULL,
    external_id text NOT NULL,
    nature text NOT NULL,
    payload jsonb NOT NULL,
    detail_fetched_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(site, external_id, nature)
);
CREATE TABLE job_matches (
    id text PRIMARY KEY,
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id text NOT NULL REFERENCES jobs(id),
    resume_version_id text NOT NULL REFERENCES resume_versions(id) ON DELETE CASCADE,
    request_id text NOT NULL,
    status text NOT NULL CHECK(status IN ('RUNNING','COMPLETED','FAILED')),
    report jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(user_id, request_id)
);
CREATE INDEX idx_job_matches_owner ON job_matches(user_id,job_id,created_at DESC);
CREATE INDEX idx_job_matches_running ON job_matches(user_id) WHERE status='RUNNING';
