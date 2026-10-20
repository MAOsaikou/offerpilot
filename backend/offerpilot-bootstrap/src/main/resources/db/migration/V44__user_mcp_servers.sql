-- Deployment owns endpoints/credentials. Users own enablement and discovered schema snapshots.
CREATE TABLE user_mcp_servers (
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider text NOT NULL CHECK (provider IN ('jobs','search','github')),
    enabled boolean NOT NULL DEFAULT true,
    tools jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(tools) = 'array'),
    status text NOT NULL DEFAULT 'NOT_REFRESHED'
        CHECK (status IN ('NOT_REFRESHED','READY','UNAVAILABLE','NOT_CONFIGURED')),
    refreshed_at timestamptz,
    PRIMARY KEY (user_id, provider)
);
