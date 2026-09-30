-- 이전 A03_refresh_tokens.sql로 테이블을 이미 만든 환경의 보정용입니다.
-- 신규 환경은 수정된 생성 SQL만 실행합니다. 운영 DB에 자동 실행되지 않습니다.
-- 기존 값이 64자 해시가 아니면 실패하여 전체 롤백됩니다. 데이터를 잘라 맞추지 않습니다.
BEGIN;
ALTER TABLE refresh_tokens DROP CONSTRAINT IF EXISTS ck_refresh_token_hash_length;
ALTER TABLE refresh_tokens ADD CONSTRAINT ck_refresh_token_hash_length CHECK (char_length(token_hash) = 64);
ALTER TABLE refresh_tokens ALTER COLUMN token_hash TYPE VARCHAR(64);
-- 이전 생성 SQL의 자동 FK 이름과 현재 명시적 이름을 처리합니다.
ALTER TABLE refresh_tokens DROP CONSTRAINT IF EXISTS refresh_tokens_user_id_fkey;
ALTER TABLE refresh_tokens DROP CONSTRAINT IF EXISTS fk_refresh_tokens_user;
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
COMMIT;
