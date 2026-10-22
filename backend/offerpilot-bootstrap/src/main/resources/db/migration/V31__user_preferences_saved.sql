-- AC-03: tells clients whether the account has saved preferences, so an unsaved table default
-- never overrides a theme the user chose locally in this browser.
ALTER TABLE user_preferences ADD COLUMN preferences_saved boolean NOT NULL DEFAULT false;
