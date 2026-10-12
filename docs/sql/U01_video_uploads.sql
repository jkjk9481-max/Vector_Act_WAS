-- U01~U05 대용량 업로드: video_uploads, video_upload_parts
-- DB 설계서 3.9~3.10 기준. 앱은 이 스크립트를 실행하지 않으므로 배포 전에 운영 DB에 적용합니다.
-- C10_analysis_tables.sql이 먼저 적용되어 있으면, 거기서 보류했던 analyses.upload_id FK를 이 스크립트 끝에서 추가합니다.

CREATE TABLE IF NOT EXISTS video_uploads (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  file_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(100) NOT NULL
     CHECK (content_type IN ('video/mp4', 'video/webm')),
  size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 2147483648),
  s3_upload_id VARCHAR(255),
  object_key VARCHAR(700) UNIQUE,
  status VARCHAR(20) NOT NULL DEFAULT 'INITIATED'
     CHECK (status IN ('INITIATED','UPLOADING','VERIFYING','READY','FAILED','CANCELED','EXPIRED')),
  part_size_bytes INTEGER NOT NULL DEFAULT 16777216 CHECK (part_size_bytes > 0),
  part_count INTEGER NOT NULL CHECK (part_count > 0),
  duration_ms INTEGER CHECK (duration_ms IS NULL OR duration_ms BETWEEN 0 AND 600000),
  expires_at TIMESTAMPTZ NOT NULL,
  failure_code VARCHAR(100),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMPTZ,
  deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_video_uploads_user_created
  ON video_uploads(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_video_uploads_expires
  ON video_uploads(expires_at);

CREATE TABLE IF NOT EXISTS video_upload_parts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  upload_id UUID NOT NULL REFERENCES video_uploads(id) ON DELETE CASCADE,
  part_number INTEGER NOT NULL CHECK (part_number >= 1),
  size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
  sha256 CHAR(64) NOT NULL,
  etag VARCHAR(255),
  status VARCHAR(20) NOT NULL DEFAULT 'RESERVED'
     CHECK (status IN ('RESERVED', 'UPLOADED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  uploaded_at TIMESTAMPTZ,
  UNIQUE (upload_id, part_number)
);

-- C10_analysis_tables.sql이 FK 없이 두었던 analyses.upload_id를 이제 연결합니다.
ALTER TABLE analyses ADD CONSTRAINT fk_analyses_upload FOREIGN KEY (upload_id)
  REFERENCES video_uploads(id) ON DELETE CASCADE;
