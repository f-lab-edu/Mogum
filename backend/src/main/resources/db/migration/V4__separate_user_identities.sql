CREATE TABLE user_identities (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider text NOT NULL CHECK (provider ~ '[^[:space:]]'),
    subject text NOT NULL CHECK (subject ~ '[^[:space:]]'),
    created_at timestamptz NOT NULL,
    UNIQUE (provider, subject)
);

CREATE INDEX user_identities_user_id_idx ON user_identities (user_id);

INSERT INTO user_identities (user_id, provider, subject, created_at)
SELECT id, 'google', google_sub, created_at FROM users;

DO $$
BEGIN
    IF (SELECT count(*) FROM users) <> (SELECT count(*) FROM user_identities)
        OR EXISTS (
            SELECT 1 FROM users u
            LEFT JOIN user_identities i ON i.user_id = u.id
                AND i.provider = 'google' AND i.subject = u.google_sub AND i.created_at = u.created_at
            WHERE i.id IS NULL
        ) THEN
        RAISE EXCEPTION 'User identity migration verification failed';
    END IF;
END $$;

ALTER TABLE users DROP COLUMN google_sub;
