ALTER TABLE files DROP CONSTRAINT files_purpose_check;
ALTER TABLE files ADD CONSTRAINT files_purpose_check
    CHECK (purpose IN ('ATTACHMENT','RESUME','AUDIO','SCREENSHOT','EXPORT','KB','KB_DOCUMENT','STAFF_KB_DOCUMENT'));

CREATE TABLE staff_knowledge_documents (
    id text PRIMARY KEY,
    title text NOT NULL,
    filename text NOT NULL,
    file_id text NOT NULL UNIQUE REFERENCES files(id) ON DELETE CASCADE,
    mime text NOT NULL,
    byte_size bigint NOT NULL CHECK (byte_size > 0 AND byte_size <= 10485760),
    content text NOT NULL,
    source_truncated boolean NOT NULL DEFAULT false,
    index_status text NOT NULL DEFAULT 'QUEUED' CHECK (index_status IN ('QUEUED','INDEXING','READY','FAILED')),
    index_error text,
    chunk_count integer NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
    revision bigint NOT NULL DEFAULT 1,
    index_attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_by text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_staff_documents_created ON staff_knowledge_documents(created_at DESC,id DESC);
CREATE INDEX idx_staff_documents_pending ON staff_knowledge_documents(next_attempt_at,id)
    WHERE index_status IN ('QUEUED','INDEXING');
CREATE TABLE staff_knowledge_chunks (
    id text PRIMARY KEY,
    document_id text NOT NULL REFERENCES staff_knowledge_documents(id) ON DELETE CASCADE,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    content text NOT NULL,
    tsv tsvector NOT NULL,
    embedding vector(1024),
    embedding_fingerprint text,
    UNIQUE(document_id,ordinal),
    CHECK ((embedding IS NULL) = (embedding_fingerprint IS NULL))
);
CREATE INDEX idx_staff_chunks_tsv ON staff_knowledge_chunks USING gin(tsv);
