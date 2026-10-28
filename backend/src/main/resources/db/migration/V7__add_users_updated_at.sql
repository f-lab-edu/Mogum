ALTER TABLE users ADD COLUMN updated_at timestamptz;

UPDATE users SET updated_at = created_at;

ALTER TABLE users ALTER COLUMN updated_at SET NOT NULL;
