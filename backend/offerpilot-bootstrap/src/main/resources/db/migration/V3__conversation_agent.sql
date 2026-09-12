CREATE TABLE conversations (
  id         text PRIMARY KEY,
  user_id    text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title      text NOT NULL DEFAULT '新对话',
  mode       text NOT NULL DEFAULT 'AUTO',
  pinned     boolean NOT NULL DEFAULT false,
  summary    text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversations_user_updated ON conversations(user_id, updated_at DESC);
CREATE TRIGGER trg_conversations_updated BEFORE UPDATE ON conversations
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE messages (
  id                 text PRIMARY KEY,
  conversation_id    text NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  user_id            text NOT NULL,
  role               text NOT NULL CHECK (role IN ('USER','ASSISTANT','SYSTEM','TOOL')),
  parts              jsonb NOT NULL,
  plain_text         text,
  run_id             text,
  model              text,
  usage              jsonb,
  created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_messages_conv_created ON messages(conversation_id, created_at);

CREATE TABLE agent_runs (
  id                   text PRIMARY KEY,
  user_id              text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  conversation_id      text NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  user_message_id      text,
  assistant_message_id text,
  mode                 text NOT NULL,
  goal                 text,
  status               text NOT NULL CHECK (status IN ('RUNNING','WAITING_APPROVAL','COMPLETED','CANCELLED','FAILED')),
  intent               jsonb,
  plan                 jsonb,
  context              jsonb NOT NULL DEFAULT '{}',
  model                text,
  error                jsonb,
  started_at           timestamptz NOT NULL DEFAULT now(),
  finished_at          timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_runs_user_started ON agent_runs(user_id, started_at DESC);
CREATE INDEX idx_agent_runs_status ON agent_runs(status) WHERE status IN ('RUNNING','WAITING_APPROVAL');

CREATE TABLE agent_steps (
  id          text PRIMARY KEY,
  run_id      text NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
  step_key    text NOT NULL,
  title       text NOT NULL,
  agent       text NOT NULL,
  inputs      jsonb,
  depends_on  text[] NOT NULL DEFAULT '{}',
  status      text NOT NULL CHECK (status IN ('PENDING','RUNNING','DONE','FAILED','SKIPPED')),
  summary     text,
  output      jsonb,
  attempt     smallint NOT NULL DEFAULT 1,
  started_at  timestamptz,
  finished_at timestamptz,
  ordinal     smallint NOT NULL
);
CREATE INDEX idx_agent_steps_run ON agent_steps(run_id, ordinal);

CREATE TABLE tool_calls (
  id          text PRIMARY KEY,
  run_id      text NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
  step_id     text REFERENCES agent_steps(id) ON DELETE CASCADE,
  name        text NOT NULL,
  source      text NOT NULL,
  access      text NOT NULL CHECK (access IN ('READ','WRITE','EXTERNAL_SIDE_EFFECT')),
  args        jsonb,
  result      jsonb,
  ok          boolean,
  error       text,
  approval_id text,
  duration_ms integer,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_tool_calls_run ON tool_calls(run_id, created_at);

CREATE TABLE approvals (
  id            text PRIMARY KEY,
  run_id        text NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
  user_id       text NOT NULL,
  tool_call_id  text,
  tool_name     text NOT NULL,
  args          jsonb NOT NULL,
  reason        text,
  status        text NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','TIMEOUT')),
  edited_args   jsonb,
  decision_note text,
  expires_at    timestamptz NOT NULL,
  decided_at    timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_approvals_run ON approvals(run_id);
CREATE INDEX idx_approvals_pending ON approvals(user_id) WHERE status = 'PENDING';

CREATE TABLE agent_events (
  run_id  text NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
  seq     integer NOT NULL,
  type    text NOT NULL,
  step_id text,
  payload jsonb NOT NULL,
  ts      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (run_id, seq)
);
