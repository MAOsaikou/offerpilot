-- Browser guest credentials are independent of the future account login flow.
CREATE TABLE guest_sessions (
  token_hash text PRIMARY KEY,
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_guest_sessions_expiry ON guest_sessions(expires_at);
ALTER TABLE messages ADD COLUMN ordinal bigserial;
ALTER TABLE messages ADD COLUMN status text NOT NULL DEFAULT 'COMPLETED'
  CHECK (status IN ('RUNNING','COMPLETED','CANCELLED','FAILED'));
CREATE UNIQUE INDEX idx_messages_conv_ordinal ON messages(conversation_id, ordinal);
ALTER TABLE agent_runs ADD COLUMN lease_expires_at timestamptz;
CREATE UNIQUE INDEX uq_conversation_active_run ON agent_runs(conversation_id)
  WHERE status IN ('RUNNING','WAITING_APPROVAL');
