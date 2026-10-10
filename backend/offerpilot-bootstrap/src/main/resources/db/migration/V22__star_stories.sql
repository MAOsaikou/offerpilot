-- Verified STAR answers reused across interview practice; writes are always user-confirmed.
CREATE TABLE star_stories (
    id text PRIMARY KEY,
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 120 AND btrim(title) <> ''),
    body text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 12000 AND btrim(body) <> ''),
    role text NOT NULL DEFAULT '' CHECK (char_length(role) <= 100),
    source_session_id text REFERENCES interview_sessions(id) ON DELETE SET NULL,
    source_question_index integer CHECK (source_question_index IS NULL OR source_question_index BETWEEN 0 AND 4),
    star_count integer NOT NULL CHECK (star_count BETWEEN 3 AND 4),
    situation boolean NOT NULL,
    task boolean NOT NULL,
    action boolean NOT NULL,
    result boolean NOT NULL,
    normalized_body text NOT NULL CHECK (normalized_body <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    tsv tsvector NOT NULL,
    UNIQUE (user_id, normalized_body),
    UNIQUE (user_id, source_session_id, source_question_index)
);
CREATE INDEX idx_star_stories_owner ON star_stories(user_id, updated_at DESC, id);
CREATE INDEX idx_star_stories_tsv ON star_stories USING gin(tsv);
CREATE TRIGGER trg_star_stories_updated BEFORE UPDATE ON star_stories
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
