-- C01 연습 세션 준비: coaching_sessions, idempotency_keys
-- DB 설계서 3.6 / 3.14 기준. 앱은 이 스크립트를 실행하지 않으므로 배포 전에 운영 DB에 적용합니다.
-- 이미 같은 이름의 객체가 있으면 건너뜁니다.

CREATE TABLE IF NOT EXISTS coaching_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  script_content TEXT NOT NULL CHECK (char_length(script_content) BETWEEN 1 AND 20000),
  situation TEXT NOT NULL CHECK (char_length(situation) BETWEEN 1 AND 2000),
  visual_enabled BOOLEAN NOT NULL,
  voice_enabled BOOLEAN NOT NULL,
  analysis_only BOOLEAN NOT NULL,
  coaching_intensity VARCHAR(20) NOT NULL
     CHECK (coaching_intensity IN ('MINIMAL', 'NORMAL', 'INTENSIVE')),
  status VARCHAR(20) NOT NULL DEFAULT 'CREATED'
     CHECK (status IN ('CREATED', 'RECORDING', 'FINALIZING', 'COMPLETED', 'FAILED', 'CANCELED')),
  video_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED'
     CHECK (video_status IN ('NOT_STARTED', 'UPLOADING', 'ASSEMBLING', 'READY', 'FAILED', 'EXPIRED', 'DELETED')),
  analysis_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED'
     CHECK (analysis_status IN ('NOT_STARTED', 'QUEUED', 'PROCESSING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELED')),
  analysis_mode VARCHAR(20) NOT NULL DEFAULT 'REALTIME'
     CHECK (analysis_mode IN ('REALTIME', 'NEAR_REALTIME', 'POST_ONLY')),
  started_at TIMESTAMPTZ,
  ended_at TIMESTAMPTZ,
  duration_ms INTEGER CHECK (duration_ms IS NULL OR duration_ms BETWEEN 0 AND 600000),
  video_expires_at TIMESTAMPTZ,
  upload_deadline_at TIMESTAMPTZ,
  analysis_attempt INTEGER NOT NULL DEFAULT 0 CHECK (analysis_attempt BETWEEN 0 AND 3),
  failure_code VARCHAR(100),
  deletion_status VARCHAR(20)
     CHECK (deletion_status IS NULL OR deletion_status IN ('PENDING', 'DONE')),
  deleted_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (NOT analysis_only OR (visual_enabled = FALSE AND voice_enabled = FALSE))
);

CREATE INDEX IF NOT EXISTS idx_coaching_sessions_user_created
  ON coaching_sessions(user_id, created_at DESC)
  WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_coaching_sessions_user_status
  ON coaching_sessions(user_id, status);

CREATE INDEX IF NOT EXISTS idx_coaching_sessions_video_expires
  ON coaching_sessions(video_expires_at)
  WHERE video_expires_at IS NOT NULL;

-- 회원당 활성 세션은 1개만 허용합니다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_coaching_sessions_one_active
  ON coaching_sessions(user_id)
  WHERE deleted_at IS NULL
    AND status IN ('CREATED','RECORDING','FINALIZING');

CREATE TABLE IF NOT EXISTS idempotency_keys (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  idempotency_key UUID NOT NULL,
  request_scope VARCHAR(100) NOT NULL,
  request_hash VARCHAR(128) NOT NULL,
  resource_id UUID,
  response_status INTEGER,
  response_body JSONB,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, idempotency_key, request_scope)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_keys_expires
  ON idempotency_keys(expires_at);
