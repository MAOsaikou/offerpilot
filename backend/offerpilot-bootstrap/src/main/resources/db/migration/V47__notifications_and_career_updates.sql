CREATE TABLE notifications (
    id text PRIMARY KEY,
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('DDL','NEW_JOB','INTERVIEW_PLAN','CAREER_TARGET','PROFILE_UPDATE')),
    dedupe_key text NOT NULL CHECK (char_length(dedupe_key) BETWEEN 1 AND 500),
    title text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    body text NOT NULL CHECK (char_length(body) <= 4000),
    link text NOT NULL CHECK (link ~ '^/[^/]'),
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz,
    UNIQUE(user_id, dedupe_key)
);
CREATE INDEX idx_notifications_owner ON notifications(user_id, created_at DESC, id DESC);
CREATE INDEX idx_notifications_unread ON notifications(user_id) WHERE read_at IS NULL;

CREATE TABLE career_target_plans (
    user_id text PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    revision text NOT NULL,
    target text NOT NULL,
    city text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','SUGGESTED','CONFIRMED','FAILED')),
    result jsonb,
    notice text NOT NULL DEFAULT '',
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_career_target_pending ON career_target_plans(status,updated_at) WHERE status IN ('PENDING','RUNNING');
-- Persist work in the same transaction for REST, approved Agent writes and memory imports.
-- Existing targets are not replayed on upgrade. Clearing the target invalidates in-flight work.
CREATE FUNCTION queue_career_target_plan() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.target = OLD.target AND NEW.city = OLD.city THEN RETURN NEW; END IF;
    END IF;
    IF NEW.target = '' THEN
        DELETE FROM career_target_plans WHERE user_id=NEW.user_id;
    ELSE
        INSERT INTO career_target_plans(user_id,revision,target,city)
        VALUES (NEW.user_id,gen_random_uuid()::text,NEW.target,NEW.city)
        ON CONFLICT(user_id) DO UPDATE SET revision=EXCLUDED.revision,target=EXCLUDED.target,
          city=EXCLUDED.city,status='PENDING',result=NULL,notice='',updated_at=now();
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_career_target_plan AFTER INSERT OR UPDATE OF target,city ON career_profiles
    FOR EACH ROW EXECUTE FUNCTION queue_career_target_plan();

CREATE TABLE career_profile_suggestions (
    id text PRIMARY KEY,
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_type text NOT NULL CHECK (source_type IN ('RESUME','GITHUB','INTERVIEW')),
    source_id text NOT NULL,
    fingerprint text NOT NULL,
    skills jsonb NOT NULL CHECK (jsonb_typeof(skills)='array'),
    facts jsonb NOT NULL CHECK (jsonb_typeof(facts)='array'),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CONFIRMED','REJECTED','STALE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz,
    UNIQUE(user_id,source_type,source_id,fingerprint)
);
CREATE INDEX idx_profile_suggestions_owner ON career_profile_suggestions(user_id,created_at DESC,id DESC);
