-- KB-01: additive public catalog; user interview snapshots remain immutable.
ALTER TABLE occupations ADD COLUMN onet_code text;
ALTER TABLE occupations ADD COLUMN source_url text;
ALTER TABLE occupations ADD COLUMN license text;
ALTER TABLE occupations ADD COLUMN attribution text;
ALTER TABLE occupations ADD COLUMN content_notice text;
ALTER TABLE occupations ADD COLUMN skills_source text;
ALTER TABLE occupations ADD COLUMN growth_path_source text;
-- Existing estimates are not sourced salary observations. Remove before the next seed sync.
UPDATE occupations SET salary_range=NULL WHERE salary_range->>'source'='自建岗位族谱估算';

CREATE TABLE builtin_knowledge_entries (
    id text PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('QUESTION','COMPETENCY','CAMPUS','REPORT')),
    title text NOT NULL,
    content text NOT NULL,
    industry text NOT NULL DEFAULT '',
    role text NOT NULL DEFAULT '',
    question_type text CHECK (question_type IN ('BEHAVIORAL','SITUATIONAL','KNOWLEDGE','CASE')),
    difficulty text CHECK (difficulty IN ('EASY','STANDARD','HARD')),
    source text NOT NULL,
    source_url text,
    license text NOT NULL,
    notice text NOT NULL,
    status text NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','PLANNED')),
    payload jsonb NOT NULL DEFAULT '{}',
    tsv tsvector NOT NULL,
    embedding vector(1024),
    embedding_fingerprint text,
    revision bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (kind <> 'QUESTION' OR (source='curated' AND question_type IS NOT NULL AND difficulty IS NOT NULL AND industry<>'' AND role<>'')),
    CHECK (kind <> 'REPORT' OR status='PLANNED')
);
CREATE INDEX idx_builtin_filters ON builtin_knowledge_entries(kind,industry,role,question_type,difficulty,id);
CREATE INDEX idx_builtin_tsv ON builtin_knowledge_entries USING gin(tsv);
CREATE INDEX idx_builtin_embedding ON builtin_knowledge_entries USING hnsw(embedding vector_cosine_ops);
