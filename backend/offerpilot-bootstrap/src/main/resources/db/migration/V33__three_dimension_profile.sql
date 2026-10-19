-- Assessment scores and traits remain owned by career_assessments.confirmed.
ALTER TABLE career_profiles
  ADD COLUMN major text NOT NULL DEFAULT '' CHECK (char_length(major) <= 100),
  ADD COLUMN courses text NOT NULL DEFAULT '' CHECK (char_length(courses) <= 3000),
  ADD COLUMN values_text text NOT NULL DEFAULT '' CHECK (char_length(values_text) <= 1000);
