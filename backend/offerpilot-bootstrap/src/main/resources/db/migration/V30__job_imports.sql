-- JB-06: jobs a user imports from pasted JD text or a page URL are private to that user.
-- Shared search snapshots keep user_id NULL; deleting the user removes their imported jobs.
ALTER TABLE jobs ADD COLUMN user_id text REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE jobs ADD CONSTRAINT jobs_imported_owner CHECK ((site = 'imported') = (user_id IS NOT NULL));
CREATE INDEX idx_jobs_imported_owner ON jobs(user_id, created_at DESC) WHERE user_id IS NOT NULL;
