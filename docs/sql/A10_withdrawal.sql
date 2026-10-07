-- 운영은 ddl-auto=none이므로 배포 전에 대상 DB에 적용합니다.
-- 기존 회원 데이터는 변경하지 않으며, 컬럼이 이미 있으면 유지합니다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS purge_at TIMESTAMPTZ;

-- 탈퇴 상태이며 삭제 예정 시간이 지난 회원을 찾는 스케줄러용 부분 인덱스입니다.
CREATE INDEX IF NOT EXISTS idx_users_withdrawal_purge
    ON users (purge_at, id) WHERE account_status = 'WITHDRAWN';
