-- C04 Chunk 업로드 URL 발급: session_chunks
-- DB 설계서 3.7 기준. 앱은 이 스크립트를 실행하지 않으므로 배포 전에 운영 DB에 적용합니다.
-- C01_coaching_sessions.sql이 먼저 적용되어 있어야 합니다. 이미 같은 이름의 객체가 있으면 건너뜁니다.

CREATE TABLE IF NOT EXISTS session_chunks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id UUID NOT NULL REFERENCES coaching_sessions(id) ON DELETE CASCADE,
  chunk_index INTEGER NOT NULL CHECK (chunk_index BETWEEN 0 AND 120),
  status VARCHAR(20) NOT NULL DEFAULT 'RESERVED'
     CHECK (status IN ('RESERVED', 'VERIFIED')),
  start_ms INTEGER NOT NULL CHECK (start_ms >= 0),
  end_ms INTEGER NOT NULL CHECK (end_ms > start_ms AND end_ms <= 600000),
  size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
  sha256 CHAR(64) NOT NULL,
  object_key VARCHAR(700) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  verified_at TIMESTAMPTZ,
  UNIQUE (session_id, chunk_index)
);
