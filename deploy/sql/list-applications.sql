-- 승인 전 이 수로 정원(운영자 포함 10명)을 확인한다. 대기자가 없어도 수는 보인다.
SELECT count(*) AS approved_count FROM users WHERE status = 'APPROVED';

-- created_at은 처음 신청한 때이며, 거절을 풀어도 그대로다.
SELECT id, email, name, status, created_at
FROM users
WHERE status IN ('APPLIED', 'REJECTED')
ORDER BY created_at, id;
