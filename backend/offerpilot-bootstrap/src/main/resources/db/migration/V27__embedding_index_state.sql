-- Which embedding model/endpoint produced the stored occupation and note vectors.
-- Switching providers (e.g. Bailian text-embedding-v4 -> local bge-m3) clears vectors for re-indexing.
CREATE TABLE embedding_index_state (
    id smallint PRIMARY KEY CHECK (id = 1),
    fingerprint text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
