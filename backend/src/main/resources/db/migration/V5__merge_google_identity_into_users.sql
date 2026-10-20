-- Fail rather than discard connections that the single-Google-account model cannot represent.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM user_identities WHERE provider <> 'google')
        OR EXISTS (
            SELECT u.id FROM users u
            LEFT JOIN user_identities i ON i.user_id = u.id
            GROUP BY u.id
            HAVING count(i.id) <> 1
        ) THEN
        RAISE EXCEPTION 'Expected exactly one Google identity per user';
    END IF;
END $$;

ALTER TABLE users ADD COLUMN google_sub text;

UPDATE users u SET google_sub = i.subject
FROM user_identities i WHERE i.user_id = u.id;

ALTER TABLE users
    ALTER COLUMN google_sub SET NOT NULL,
    ADD CONSTRAINT users_google_sub_key UNIQUE (google_sub),
    ADD CONSTRAINT users_google_sub_not_blank CHECK (google_sub ~ '[^[:space:]]');

DROP TABLE user_identities;
