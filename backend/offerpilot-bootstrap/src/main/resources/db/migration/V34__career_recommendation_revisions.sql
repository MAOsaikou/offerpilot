CREATE TABLE career_recommendation_revisions (
    id text PRIMARY KEY,
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_id text,
    snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (user_id, id),
    FOREIGN KEY (user_id, parent_id) REFERENCES career_recommendation_revisions(user_id, id)
        ON DELETE CASCADE
);
CREATE INDEX idx_career_revision_owner ON career_recommendation_revisions(user_id, created_at DESC, id);
