CREATE TABLE resume_optimizations (
  id text PRIMARY KEY,
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  resume_id text NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
  version_id text NOT NULL REFERENCES resume_versions(id) ON DELETE CASCADE,
  request_id text NOT NULL,
  status text NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','CANCELLED')),
  model_tier text NOT NULL,
  job_description text NOT NULL,
  report jsonb,
  error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, request_id),
  CHECK ((status = 'SUCCEEDED') = (report IS NOT NULL))
);
CREATE UNIQUE INDEX uq_resume_optimizations_running ON resume_optimizations(user_id) WHERE status = 'RUNNING';
CREATE INDEX idx_resume_optimizations_resume ON resume_optimizations(user_id, resume_id, created_at DESC);
