-- CD-01/CD-02: conversational assessment answers, AI inference and the user-confirmed profile.
CREATE TABLE career_assessments (
    user_id      text PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    answers      jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(answers) = 'array'),
    inference    jsonb,
    confirmed    jsonb,
    confirmed_at timestamptz,
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_career_assessments_updated BEFORE UPDATE ON career_assessments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
