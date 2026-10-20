-- Per provider invocation, no prompts, outputs or credentials.
CREATE TABLE llm_usage_logs (
 id text PRIMARY KEY,
 user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 run_id text NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
 message_id text NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
 model text NOT NULL,
 prompt_tokens integer NOT NULL CHECK(prompt_tokens >= 0),
 completion_tokens integer NOT NULL CHECK(completion_tokens >= 0),
 duration_ms bigint NOT NULL CHECK(duration_ms >= 0),
 cost_estimate numeric(24,10) CHECK(cost_estimate >= 0),
 usage_reported boolean NOT NULL,
 status text NOT NULL CHECK(status IN ('COMPLETED','FAILED','CANCELLED')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_llm_usage_message ON llm_usage_logs(user_id,message_id);
CREATE INDEX idx_llm_usage_run ON llm_usage_logs(run_id);
