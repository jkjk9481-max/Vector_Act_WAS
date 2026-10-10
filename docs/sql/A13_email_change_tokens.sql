-- 기존 A09 DDL 적용 후 배포 과정에서 실행합니다. 애플리케이션이 자동 실행하지 않습니다.
BEGIN;
ALTER TABLE auth_one_time_tokens ADD COLUMN new_email VARCHAR(254);
ALTER TABLE auth_one_time_tokens DROP CONSTRAINT IF EXISTS auth_one_time_tokens_token_type_check;
-- 초기 purpose 컬럼을 token_type으로 정렬한 DB의 제약 이름도 처리합니다.
ALTER TABLE auth_one_time_tokens DROP CONSTRAINT IF EXISTS auth_one_time_tokens_purpose_check;
ALTER TABLE auth_one_time_tokens ADD CONSTRAINT auth_one_time_tokens_token_type_check
    CHECK (token_type IN ('PASSWORD_RESET', 'REAUTH', 'EMAIL_CHANGE'));
ALTER TABLE auth_one_time_tokens ADD CONSTRAINT auth_one_time_tokens_new_email_check
    CHECK ((token_type = 'EMAIL_CHANGE' AND new_email IS NOT NULL)
        OR (token_type <> 'EMAIL_CHANGE' AND new_email IS NULL));
COMMIT;
