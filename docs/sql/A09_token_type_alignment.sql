-- 이전 A09 DDL의 purpose 컬럼으로 테이블을 이미 만든 DB에만 한 번 적용합니다.
-- 신규 DB는 A09_auth_one_time_tokens.sql만 실행하면 됩니다. 이 파일은 자동 실행되지 않습니다.
-- 컬럼 이름만 바꾸므로 기존 토큰 데이터와 UNIQUE/외래 키/검사 제약은 유지됩니다.
-- PostgreSQL은 해당 컬럼을 참조하는 CHECK 제약의 정의도 새 이름에 맞게 연결합니다.
ALTER TABLE auth_one_time_tokens RENAME COLUMN purpose TO token_type;
