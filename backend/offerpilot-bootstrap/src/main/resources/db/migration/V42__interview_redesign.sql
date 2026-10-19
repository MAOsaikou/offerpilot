-- Additive snapshots: NULL generation_source preserves legacy sessions and rubric reports.
ALTER TABLE interview_sessions ADD COLUMN generation_source text CHECK (generation_source IN ('pending','model','rule'));
ALTER TABLE interview_sessions ADD COLUMN resume_context text;
ALTER TABLE interview_sessions ADD COLUMN job_context text;
ALTER TABLE interview_sessions ADD COLUMN report jsonb;
ALTER TABLE interview_sessions ADD COLUMN revision bigint NOT NULL DEFAULT 0;
ALTER TABLE interview_answers ADD COLUMN question_type text;
ALTER TABLE interview_answers ADD COLUMN difficulty text;
ALTER TABLE interview_answers ADD COLUMN question_source text;
ALTER TABLE interview_answers ADD COLUMN evaluation jsonb;
ALTER TABLE interview_sessions DROP CONSTRAINT interview_sessions_question_index_check;
ALTER TABLE interview_sessions ADD CONSTRAINT interview_sessions_question_index_check CHECK (question_index BETWEEN 0 AND 14);
ALTER TABLE interview_answers DROP CONSTRAINT interview_answers_question_index_check;
ALTER TABLE interview_answers ADD CONSTRAINT interview_answers_question_index_check CHECK (question_index BETWEEN 0 AND 14);
ALTER TABLE star_stories DROP CONSTRAINT star_stories_source_question_index_check;
ALTER TABLE star_stories ADD CONSTRAINT star_stories_source_question_index_check CHECK (source_question_index IS NULL OR source_question_index BETWEEN 0 AND 14);
