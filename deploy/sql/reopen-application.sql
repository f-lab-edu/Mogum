-- psql -v user_id=123 -f reopen-application.sql
UPDATE users SET status = 'APPLIED', updated_at = now()
WHERE id = :'user_id'::bigint AND status = 'REJECTED';
