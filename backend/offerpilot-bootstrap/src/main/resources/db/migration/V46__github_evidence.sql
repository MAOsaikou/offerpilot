CREATE TABLE github_evidence (
    user_id text PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    username text NOT NULL CHECK (char_length(username) BETWEEN 1 AND 39),
    token_ciphertext text,
    revision text NOT NULL,
    snapshot jsonb,
    fetched_at timestamptz,
    last_attempt_at timestamptz,
    status text NOT NULL DEFAULT 'NOT_FETCHED' CHECK (status IN
        ('NOT_FETCHED','READY','STATISTICS_ONLY','UNAVAILABLE','RATE_LIMITED','AUTH_FAILED','NOT_FOUND')),
    notice text NOT NULL DEFAULT '',
    CHECK (snapshot IS NULL OR jsonb_typeof(snapshot)='object'),
    CHECK ((snapshot IS NULL) = (fetched_at IS NULL))
);
