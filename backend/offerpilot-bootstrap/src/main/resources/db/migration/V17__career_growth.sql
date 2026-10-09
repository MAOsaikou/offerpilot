-- V16 is reserved for the independent interview slice.
CREATE FUNCTION career_interests_valid(value jsonb) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT CASE WHEN jsonb_typeof(value) = 'array' THEN
    jsonb_array_length(value) <= 20 AND NOT EXISTS (
      SELECT 1 FROM jsonb_array_elements(value) AS item
      WHERE jsonb_typeof(item) <> 'string' OR char_length(item #>> '{}') > 50
    )
  ELSE false END
$$;

CREATE TABLE career_profiles (
  user_id    text PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  target     text NOT NULL DEFAULT '' CHECK (char_length(target) <= 100),
  city       text NOT NULL DEFAULT '' CHECK (char_length(city) <= 100),
  skills     text NOT NULL DEFAULT '' CHECK (char_length(skills) <= 3000),
  interests  jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (career_interests_valid(interests)),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_career_profiles_updated BEFORE UPDATE ON career_profiles
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE growth_task_completions (
  user_id      text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  task_title   text NOT NULL,
  completed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, task_title)
);
CREATE INDEX idx_growth_task_completions_owner
  ON growth_task_completions(user_id, completed_at DESC);
