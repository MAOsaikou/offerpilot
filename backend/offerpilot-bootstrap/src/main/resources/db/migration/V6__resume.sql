CREATE TABLE resumes (
  id text PRIMARY KEY,
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title text NOT NULL,
  source_file_id text NOT NULL REFERENCES files(id),
  current_version_id text,
  parse_status text NOT NULL DEFAULT 'QUEUED' CHECK (parse_status IN ('QUEUED','PARSING','READY','FAILED')),
  parse_progress smallint NOT NULL DEFAULT 0,
  parse_error text,
  parse_token text,
  parse_started_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_resumes_user ON resumes(user_id, created_at DESC);
CREATE INDEX idx_resumes_queue ON resumes(created_at) WHERE parse_status = 'QUEUED';
CREATE TABLE resume_versions (
  id text PRIMARY KEY,
  resume_id text NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  version_no integer NOT NULL,
  parent_version_id text REFERENCES resume_versions(id),
  source text NOT NULL CHECK (source IN ('UPLOAD','MANUAL_EDIT')),
  structured jsonb NOT NULL,
  plain_text text NOT NULL,
  parse_meta jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (resume_id, version_no)
);
CREATE INDEX idx_resume_versions_resume ON resume_versions(resume_id, version_no DESC);
ALTER TABLE resumes ADD CONSTRAINT fk_resume_current FOREIGN KEY (current_version_id) REFERENCES resume_versions(id);
