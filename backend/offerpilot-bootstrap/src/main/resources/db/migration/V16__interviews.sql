CREATE TABLE interview_sessions (
    id text PRIMARY KEY,
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role text NOT NULL CHECK (char_length(role) BETWEEN 1 AND 100),
    kind text NOT NULL CHECK (kind IN ('项目与经历', '表达与结构', '职业动机')),
    question_index integer NOT NULL DEFAULT 0 CHECK (question_index BETWEEN 0 AND 4),
    completed boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_interview_sessions_owner ON interview_sessions(user_id, created_at DESC, id);

CREATE TABLE interview_answers (
    session_id text NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
    question_index integer NOT NULL CHECK (question_index BETWEEN 0 AND 4),
    question_text text NOT NULL,
    answer_text text NOT NULL DEFAULT '' CHECK (char_length(answer_text) <= 12000),
    PRIMARY KEY (session_id, question_index)
);
