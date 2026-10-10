-- Transient external cleanup receipts survive user cascade; no email/content/credentials.
CREATE TABLE account_data_cleanup (
    id text PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('OBJECT', 'QUOTA')),
    bucket text,
    object_key text NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((kind = 'OBJECT') = (bucket IS NOT NULL))
);
CREATE INDEX idx_account_data_cleanup_pending ON account_data_cleanup(next_attempt_at);

-- Previously only indirectly cascaded via conversation/run; enforce direct ownership too.
ALTER TABLE messages ADD CONSTRAINT fk_messages_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE approvals ADD CONSTRAINT fk_approvals_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
