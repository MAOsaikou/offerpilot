ALTER TABLE resume_versions DROP CONSTRAINT resume_versions_source_check;
ALTER TABLE resume_versions ADD CONSTRAINT resume_versions_source_check
  CHECK (source IN ('UPLOAD','MANUAL_EDIT','OPTIMIZED'));

CREATE TABLE resume_patch_batches (
  id text PRIMARY KEY,
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  resume_id text NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
  resume_version_id text NOT NULL REFERENCES resume_versions(id) ON DELETE CASCADE,
  patches jsonb NOT NULL CHECK (jsonb_typeof(patches) = 'array' AND jsonb_array_length(patches) BETWEEN 1 AND 50),
  applied_version_id text REFERENCES resume_versions(id),
  decision jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((applied_version_id IS NULL) = (decision IS NULL))
);
CREATE INDEX idx_resume_patch_batches_version ON resume_patch_batches(user_id, resume_version_id, created_at DESC);
