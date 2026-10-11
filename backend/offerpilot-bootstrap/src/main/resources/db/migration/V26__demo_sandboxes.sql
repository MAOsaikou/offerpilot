-- NULL retains legacy shared-demo and ordinary-user behaviour.
ALTER TABLE users ADD COLUMN demo_expires_at timestamptz;
ALTER TABLE users ADD CONSTRAINT ck_demo_sandbox CHECK (
    demo_expires_at IS NULL OR (is_demo AND roles = ARRAY['USER']::text[])
);
CREATE INDEX idx_users_demo_expiry ON users(demo_expires_at) WHERE demo_expires_at IS NOT NULL;
-- Aggregate only: deletion never refunds a day's admission; no IP or visitor identifiers.
CREATE TABLE demo_sandbox_admissions (
    day date PRIMARY KEY,
    created_count integer NOT NULL CHECK (created_count >= 0)
);
