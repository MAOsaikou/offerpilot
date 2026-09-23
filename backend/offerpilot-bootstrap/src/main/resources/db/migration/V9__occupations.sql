-- 职业条目库（内置知识库）。
-- 字段定义见 docs/06；embedding 与 tsv 供后续 RAG 阶段填充，此处先建列与索引。
-- 结构化字段（holland_code / holland_scores / core_skills）供职业推荐的规则层直接使用，
-- 不依赖向量检索，因此 RAG 接入前也能跑通推荐流程。
CREATE TABLE occupations (
  id             text PRIMARY KEY,
  code           text UNIQUE,
  name           text NOT NULL,
  aliases        text[] NOT NULL DEFAULT '{}',
  family         text NOT NULL,
  description    text NOT NULL,
  holland_code   char(3),
  holland_scores jsonb,
  core_skills    jsonb NOT NULL DEFAULT '[]',
  typical_titles text[] NOT NULL DEFAULT '{}',
  growth_path    text,
  salary_range   jsonb,
  outlook        text,
  companies      text[] NOT NULL DEFAULT '{}',
  related_majors text[] NOT NULL DEFAULT '{}',
  source         text NOT NULL,
  embedding      vector(1024),
  tsv            tsvector,
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_occupations_family ON occupations(family);
CREATE INDEX idx_occupations_holland ON occupations(holland_code);
CREATE INDEX idx_occupations_embedding ON occupations USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_occupations_tsv ON occupations USING gin (tsv);
CREATE INDEX idx_occupations_name_trgm ON occupations USING gin (name gin_trgm_ops);
