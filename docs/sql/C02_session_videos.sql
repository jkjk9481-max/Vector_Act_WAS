-- C02 촬영 시작: session_videos
-- DB 설계서 3.8 기준. 앱은 이 스크립트를 실행하지 않으므로 배포 전에 운영 DB에 적용합니다.
-- C01_coaching_sessions.sql이 먼저 적용되어 있어야 합니다. 이미 같은 이름의 객체가 있으면 건너뜁니다.

CREATE TABLE IF NOT EXISTS session_videos (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id UUID NOT NULL UNIQUE REFERENCES coaching_sessions(id) ON DELETE CASCADE,
  input_content_type VARCHAR(100),
  stored_content_type VARCHAR(100),
  width INTEGER CHECK (width IS NULL OR width BETWEEN 1 AND 1920),
  height INTEGER CHECK (height IS NULL OR height BETWEEN 1 AND 1080),
  frame_rate NUMERIC(5,2) CHECK (frame_rate IS NULL OR frame_rate BETWEEN 1 AND 30),
  object_key VARCHAR(700) UNIQUE,
  size_bytes BIGINT CHECK (size_bytes IS NULL OR size_bytes > 0),
  duration_ms INTEGER CHECK (duration_ms IS NULL OR duration_ms BETWEEN 0 AND 600000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMPTZ,
  deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_session_videos_expires
  ON session_videos(expires_at)
  WHERE deleted_at IS NULL;
