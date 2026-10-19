-- V34 is cached origin/main; leave room for concurrent career migrations.
ALTER TABLE interview_sessions ADD COLUMN options jsonb;
ALTER TABLE interview_sessions DROP CONSTRAINT interview_sessions_kind_check;
ALTER TABLE interview_sessions ADD CONSTRAINT interview_sessions_kind_check
    CHECK (kind IN ('项目与经历','表达与结构','职业动机','技术','行为','HR','综合'));
ALTER TABLE interview_sessions DROP CONSTRAINT interview_sessions_question_index_check;
ALTER TABLE interview_sessions ADD CONSTRAINT interview_sessions_question_index_check CHECK (question_index BETWEEN 0 AND 9);
ALTER TABLE interview_answers DROP CONSTRAINT interview_answers_question_index_check;
ALTER TABLE interview_answers ADD CONSTRAINT interview_answers_question_index_check CHECK (question_index BETWEEN 0 AND 9);
ALTER TABLE interview_sessions ADD CONSTRAINT uq_interview_sessions_owner_id UNIQUE(user_id,id);
CREATE TABLE interview_learning_plans (
    id text PRIMARY KEY,
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    report_id text NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
    roadmap jsonb NOT NULL,
    start_date date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(user_id, report_id),
    FOREIGN KEY(user_id,report_id) REFERENCES interview_sessions(user_id,id) ON DELETE CASCADE
);
CREATE INDEX idx_interview_learning_plans_owner ON interview_learning_plans(user_id, created_at DESC);
