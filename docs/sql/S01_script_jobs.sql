-- 운영은 ddl-auto=none이므로 배포 전에 대상 DB에 적용합니다.
-- 최종 DB 설계서 3.5 script_jobs 기준입니다. 테이블이 이미 있으면 유지합니다.
-- gen_random_uuid()는 pgcrypto 확장이 필요합니다(PostgreSQL 13 이상은 기본 제공).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS script_jobs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  job_type VARCHAR(30) NOT NULL
     CHECK (job_type IN ('OCR')),
  status VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
     CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
  request_data JSONB,
  result_content TEXT,
  source_object_key VARCHAR(700),
  failure_code VARCHAR(100),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ
);

-- 본인 작업 조회와 24시간 TTL 정리용 인덱스입니다.
CREATE INDEX IF NOT EXISTS idx_script_jobs_user_created
  ON script_jobs(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_script_jobs_expires
  ON script_jobs(expires_at);
