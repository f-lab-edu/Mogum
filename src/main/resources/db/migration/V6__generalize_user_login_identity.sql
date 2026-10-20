ALTER TABLE users RENAME COLUMN google_sub TO subject;

-- Backfill existing Google accounts, then require an explicit provider for new users.
ALTER TABLE users ADD COLUMN provider text NOT NULL DEFAULT 'google';
ALTER TABLE users ALTER COLUMN provider DROP DEFAULT;

ALTER TABLE users DROP CONSTRAINT users_google_sub_key;
ALTER TABLE users RENAME CONSTRAINT users_google_sub_not_blank TO users_subject_not_blank;
ALTER TABLE users
    ADD CONSTRAINT users_provider_not_blank CHECK (provider ~ '[^[:space:]]'),
    ADD CONSTRAINT users_provider_subject_key UNIQUE (provider, subject);
