-- 운영은 ddl-auto: none이므로 배포 과정에서 적용해야 하는 PostgreSQL DDL입니다.
-- 이 파일을 추가하는 것만으로 운영 DB에 자동 실행되지 않습니다. users 테이블이 먼저 필요합니다.
CREATE TABLE auth_one_time_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL CONSTRAINT fk_auth_one_time_tokens_user REFERENCES users(id) ON DELETE CASCADE,
    purpose VARCHAR(30) NOT NULL CHECK (purpose IN ('PASSWORD_RESET', 'REAUTH')),
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_auth_one_time_tokens_user_id ON auth_one_time_tokens(user_id);
CREATE INDEX idx_auth_one_time_tokens_expires_at ON auth_one_time_tokens(expires_at);
-- REAUTH는 생성 시각 + 5분으로 애플리케이션이 만료 시각을 설정합니다.
-- PASSWORD_RESET은 문서상 15분이며, 해당 발급 API를 구현할 때 적용합니다.
