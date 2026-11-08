-- Unknown states must stop the migration rather than silently become applications.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE status NOT IN ('ACTIVE', 'PENDING', 'WAITLISTED')) THEN
        RAISE EXCEPTION 'Unsupported legacy user status';
    END IF;
END $$;

ALTER TABLE users
    DROP CONSTRAINT users_status_check,
    ADD COLUMN name text,
    ADD COLUMN approved_at timestamptz,
    ADD COLUMN credit_balance integer,
    ADD COLUMN credit_refill_at timestamptz;

-- now() is the transaction timestamp, shared by approvals and their initial credit entries.
-- Subtract four hours in Seoul before choosing the approval date, then add 30 calendar days.
UPDATE users SET
    name = split_part(email, '@', 1),
    approved_at = CASE WHEN status = 'ACTIVE' THEN now() ELSE NULL END,
    credit_refill_at = CASE WHEN status = 'ACTIVE' THEN
        (((now() AT TIME ZONE 'Asia/Seoul' - interval '4 hours')::date + 30 + time '04:00') AT TIME ZONE 'Asia/Seoul')
        ELSE NULL END,
    credit_balance = 100,
    status = CASE WHEN status = 'ACTIVE' THEN 'APPROVED' ELSE 'APPLIED' END,
    updated_at = now();

ALTER TABLE users
    DROP COLUMN role,
    DROP COLUMN terms_agreed_at,
    DROP COLUMN terms_version,
    ALTER COLUMN name SET NOT NULL,
    ALTER COLUMN credit_balance SET NOT NULL,
    ADD CONSTRAINT users_email_not_blank CHECK (email ~ '[^[:space:]]'),
    ADD CONSTRAINT users_name_not_blank CHECK (name ~ '[^[:space:]]'),
    ADD CONSTRAINT users_status_check CHECK (status IN ('APPLIED', 'APPROVED', 'REJECTED')),
    ADD CONSTRAINT users_credit_balance_check CHECK (credit_balance >= 0),
    ADD CONSTRAINT users_approved_at_check CHECK ((status = 'APPROVED') = (approved_at IS NOT NULL)),
    ADD CONSTRAINT users_credit_refill_at_check CHECK ((status = 'APPROVED') = (credit_refill_at IS NOT NULL));

CREATE TABLE credit_entries (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id),
    type text NOT NULL,
    amount integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT credit_entries_type_check CHECK (type IN ('REFILL', 'CONSUME', 'RESTORE'))
);

CREATE INDEX credit_entries_user_id_created_at_idx ON credit_entries(user_id, created_at);

-- Historical initial allocation: do not read the current monthly amount from configuration.
INSERT INTO credit_entries (user_id, type, amount, created_at, updated_at)
SELECT id, 'REFILL', 100, now(), now() FROM users;
