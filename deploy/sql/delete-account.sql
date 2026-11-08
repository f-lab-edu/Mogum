-- psql -v ON_ERROR_STOP=1 -v user_id=123 -f delete-account.sql
BEGIN;

-- 삭제 중 승인이 끼어들지 않게 대상 행을 트랜잭션이 끝날 때까지 잠근다.
SELECT id FROM users
WHERE id = :'user_id'::bigint AND status IN ('APPLIED', 'REJECTED')
FOR UPDATE;

DELETE FROM credit_entries
WHERE user_id IN (
    SELECT id FROM users WHERE id = :'user_id'::bigint AND status IN ('APPLIED', 'REJECTED')
);

-- 주인 색인은 String 사용자 ID다. V3의 spring_session_attributes FK가
-- ON DELETE CASCADE이므로 세션 속성은 세션과 함께 지워진다.
DELETE FROM spring_session
WHERE principal_name IN (
    SELECT id::text FROM users WHERE id = :'user_id'::bigint AND status IN ('APPLIED', 'REJECTED')
);

DELETE FROM users
WHERE id = :'user_id'::bigint AND status IN ('APPLIED', 'REJECTED');

COMMIT;
