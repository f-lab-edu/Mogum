-- psql -v user_id=123 -f approve-account.sql
-- 승인한 날은 서울 새벽 4시 경계로 고른다. 첫 크레딧과 채움 기록은 신청 때 이미 생겼다.
UPDATE users SET
    status = 'APPROVED',
    approved_at = now(),
    credit_refill_at = (((now() AT TIME ZONE 'Asia/Seoul' - interval '4 hours')::date + 30 + time '04:00') AT TIME ZONE 'Asia/Seoul'),
    updated_at = now()
WHERE id = :'user_id'::bigint AND status = 'APPLIED';
