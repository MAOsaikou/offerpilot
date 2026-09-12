CREATE TABLE users (
  id             text PRIMARY KEY,
  email          citext UNIQUE NOT NULL,
  password_hash  text NOT NULL,
  nickname       text NOT NULL,
  avatar_file_id text,
  school         text,
  school_type    text CHECK (school_type IN ('KEY_985_211','REGULAR_UNDERGRAD','VOCATIONAL','GRADUATE')),
  major          text,
  grade          text,
  roles          text[] NOT NULL DEFAULT '{USER}',
  is_demo        boolean NOT NULL DEFAULT false,
  status         text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED','DELETED')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_users_updated BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE user_preferences (
  user_id            text PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  default_model_tier text NOT NULL DEFAULT 'FLASH' CHECK (default_model_tier IN ('FLASH','CHEAP','PRO')),
  language           text NOT NULL DEFAULT 'zh-CN',
  theme              text NOT NULL DEFAULT 'system',
  pii_masking        boolean NOT NULL DEFAULT true,
  voice_auto_play    boolean NOT NULL DEFAULT true,
  camera_analysis    boolean NOT NULL DEFAULT true,
  daily_token_limit  integer NOT NULL DEFAULT 200000,
  updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_user_preferences_updated BEFORE UPDATE ON user_preferences
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE refresh_tokens (
  id         text PRIMARY KEY,
  user_id    text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash text NOT NULL UNIQUE,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  user_agent text,
  ip         inet,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);

CREATE TABLE user_integrations (
  id         text PRIMARY KEY,
  user_id    text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider   text NOT NULL CHECK (provider IN ('GITHUB')),
  secret_enc bytea NOT NULL,
  meta       jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, provider)
);
CREATE TRIGGER trg_user_integrations_updated BEFORE UPDATE ON user_integrations
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
