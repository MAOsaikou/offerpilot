-- Candidates never enter recall until explicitly confirmed. Rejected facts remain dedupe tombstones.
CREATE TABLE memory_candidates (
    id text PRIMARY KEY,
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('GOAL','LOCATION','SKILL','PREFERENCE','CONSTRAINT','OTHER')),
    fact text NOT NULL CHECK (char_length(fact) BETWEEN 1 AND 200),
    normalized_fact text NOT NULL,
    confidence double precision NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    extractor text NOT NULL CHECK (extractor IN ('model','rule')),
    source_conversation_id text REFERENCES conversations(id) ON DELETE CASCADE,
    source_message_id text REFERENCES messages(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','DELETED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(user_id,normalized_fact)
);
CREATE INDEX memory_candidates_pending ON memory_candidates(user_id,created_at) WHERE status='PENDING';
ALTER TABLE career_assessments ADD COLUMN followups jsonb NOT NULL DEFAULT '[]'::jsonb;
CREATE TABLE career_questionnaires (
    user_id text PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    answers jsonb NOT NULL DEFAULT '{}'::jsonb,
    revision integer NOT NULL DEFAULT 0,
    completed_revision integer,
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE agent_runs ADD COLUMN reconnect_until timestamptz;
-- Distinguish committed run status from the last event actually flushed by the producer.
ALTER TABLE agent_runs ADD COLUMN events_finished boolean NOT NULL DEFAULT false;
UPDATE agent_runs SET events_finished=true WHERE status IN ('COMPLETED','CANCELLED','FAILED');
