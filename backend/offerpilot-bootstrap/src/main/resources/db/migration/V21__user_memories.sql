-- Explicit, owner-controlled facts; no automatic ingestion from documents or model replies.
ALTER TABLE user_preferences ADD COLUMN memory_enabled boolean NOT NULL DEFAULT true;
CREATE TABLE user_memories (
    id text PRIMARY KEY,
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('GOAL','LOCATION','SKILL','PREFERENCE','CONSTRAINT','OTHER')),
    fact text NOT NULL CHECK (char_length(fact) BETWEEN 1 AND 200 AND btrim(fact) <> ''),
    normalized_fact text NOT NULL CHECK (normalized_fact <> ''),
    source text NOT NULL CHECK (source IN ('EXPLICIT','SUGGESTED_ACCEPTED','MANUAL')),
    source_conversation_id text REFERENCES conversations(id) ON DELETE SET NULL,
    source_message_id text REFERENCES messages(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    tsv tsvector NOT NULL,
    UNIQUE(user_id, normalized_fact)
);
CREATE INDEX idx_user_memories_owner ON user_memories(user_id, updated_at DESC, id);
CREATE INDEX idx_user_memories_tsv ON user_memories USING gin(tsv);
CREATE TRIGGER trg_user_memories_updated BEFORE UPDATE ON user_memories
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
