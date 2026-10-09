-- User-authored evidence, separate from the public V9 occupation corpus.
CREATE TABLE knowledge_notes (
    id text PRIMARY KEY,
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 120 AND btrim(title) <> ''),
    content text NOT NULL CHECK (char_length(content) BETWEEN 1 AND 30000 AND btrim(content) <> ''),
    category text NOT NULL CHECK (category IN ('面试复盘', '技术积累', '项目经历', '求职心得')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    revision bigint NOT NULL DEFAULT 1,
    -- Maintained atomically with content by KnowledgeNoteService, using V9 Han bigrams.
    tsv tsvector NOT NULL,
    embedding vector(1024),
    embedding_next_attempt_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_knowledge_notes_owner ON knowledge_notes(user_id, updated_at DESC, id DESC);
CREATE INDEX idx_knowledge_notes_tsv ON knowledge_notes USING gin(tsv);
-- Private corpora are small: exact cosine search AFTER owner filtering avoids ANN under-recall.
CREATE INDEX idx_knowledge_notes_pending ON knowledge_notes(embedding_next_attempt_at) WHERE embedding IS NULL;

-- Durable receipts prevent duplicate imports even after a user edits/deletes the imported note.
CREATE TABLE knowledge_note_imports (
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_id text NOT NULL CHECK (char_length(source_id) BETWEEN 1 AND 100),
    fingerprint char(64) NOT NULL,
    note_id text REFERENCES knowledge_notes(id) ON DELETE SET NULL,
    imported_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, source_id)
);
