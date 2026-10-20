-- V42 is the previous maximum. Existing payloads remain valid; absent deadline stays unknown.
ALTER TABLE jobs ADD COLUMN search_embedding vector(1024),
    ADD COLUMN embedding_key text, ADD COLUMN embedding_hash text;
CREATE INDEX idx_jobs_embedding ON jobs USING hnsw(search_embedding vector_cosine_ops)
    WHERE search_embedding IS NOT NULL;
-- One current daily snapshot per owner; cascades on account deletion.
CREATE TABLE job_daily_recommendations (
    user_id text PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    day date NOT NULL,
    context_hash text NOT NULL,
    snapshot jsonb NOT NULL
);
