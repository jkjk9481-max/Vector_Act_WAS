-- 운영은 ddl-auto=none이므로 배포 전에 대상 DB에 적용합니다.
-- 기존 회원 데이터는 변경하지 않으며, 컬럼이 이미 있으면 유지합니다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS purge_at TIMESTAMPTZ;

-- 최종 DB 설계서 기준: 논리 삭제 시각이 있는 회원의 삭제 예정 시각만 인덱싱합니다.
-- 부분 인덱스는 WHERE 조건을 만족하는 행만 포함합니다.
-- 같은 이름의 인덱스가 이미 존재하면 IF NOT EXISTS는 기존 정의를 변경하지 않습니다.
CREATE INDEX IF NOT EXISTS idx_users_withdrawal_purge
    ON users (purge_at)
    WHERE deleted_at IS NOT NULL;
