ALTER TABLE files DROP CONSTRAINT files_purpose_check;
ALTER TABLE files ADD CONSTRAINT files_purpose_check
    CHECK (purpose IN ('ATTACHMENT','RESUME','AUDIO','SCREENSHOT','EXPORT','KB','KB_DOCUMENT'));

ALTER TABLE knowledge_notes
    ADD COLUMN source_file_id text REFERENCES files(id) ON DELETE SET NULL,
    ADD COLUMN source_filename text,
    ADD COLUMN source_truncated boolean NOT NULL DEFAULT false,
    ADD COLUMN index_status text NOT NULL DEFAULT 'READY'
        CHECK (index_status IN ('QUEUED','INDEXING','READY','FAILED')),
    ADD COLUMN index_error text CHECK (char_length(index_error) <= 200),
    ADD COLUMN embedding_attempts integer NOT NULL DEFAULT 0 CHECK (embedding_attempts >= 0);
CREATE UNIQUE INDEX idx_knowledge_notes_source_file ON knowledge_notes(source_file_id) WHERE source_file_id IS NOT NULL;
