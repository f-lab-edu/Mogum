-- psql -v user_id=123 -f reject-account.sql
UPDATE users SET status = 'REJECTED', updated_at = now()
WHERE id = :'user_id'::bigint AND status = 'APPLIED';
