CREATE TABLE files (
  id         text PRIMARY KEY,
  user_id    text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose    text NOT NULL CHECK (purpose IN ('ATTACHMENT','RESUME','AUDIO','SCREENSHOT','EXPORT','KB')),
  bucket     text NOT NULL,
  object_key text NOT NULL,
  name       text NOT NULL,
  mime       text NOT NULL,
  size       bigint NOT NULL,
  sha256     text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (bucket, object_key)
);
CREATE INDEX idx_files_user ON files(user_id, created_at DESC);
