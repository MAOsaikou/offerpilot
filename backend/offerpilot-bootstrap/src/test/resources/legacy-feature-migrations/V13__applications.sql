CREATE TABLE applications (
    id text PRIMARY KEY,
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id text NOT NULL REFERENCES jobs(id),
    status text NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO','APPLIED','INTERVIEW','OFFER','CLOSED')),
    note text NOT NULL DEFAULT '' CHECK (char_length(note) <= 2000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(user_id, job_id)
);
CREATE INDEX idx_applications_owner ON applications(user_id, created_at DESC, id);
