-- C10 분석 결과 조회: analyses, analysis_scores, analysis_feedbacks
-- DB 설계서 3.11 ~ 3.13 기준. 앱은 이 스크립트를 실행하지 않으므로 배포 전에 운영 DB에 적용합니다.
-- C01_coaching_sessions.sql이 먼저 적용되어 있어야 합니다. 이미 같은 이름의 객체가 있으면 건너뜁니다.
--
-- 주의: DB 설계서의 analyses.upload_id는 video_uploads(업로드 분석, U 시리즈)를 참조합니다.
-- video_uploads 테이블이 아직 없으므로 여기서는 FK 없이 컬럼만 둡니다.
-- video_uploads를 만들 때 FK를 추가하세요:
--   ALTER TABLE analyses ADD CONSTRAINT fk_analyses_upload FOREIGN KEY (upload_id)
--     REFERENCES video_uploads(id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS analyses (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source VARCHAR(30) NOT NULL
     CHECK (source IN ('LIVE_CAPTURE', 'UPLOADED_VIDEO')),
  session_id UUID REFERENCES coaching_sessions(id) ON DELETE CASCADE,
  upload_id UUID,
  status VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
     CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','PARTIAL','FAILED','CANCELED')),
  attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt BETWEEN 1 AND 3),
  schema_version VARCHAR(30),
  model_version VARCHAR(100),
  scoring_version VARCHAR(100),
  duration_ms INTEGER CHECK (duration_ms IS NULL OR duration_ms BETWEEN 0 AND 600000),
  overall_score NUMERIC(5,2)
     CHECK (overall_score IS NULL OR overall_score BETWEEN 0 AND 100),
  summary TEXT,
  strengths JSONB,
  improvements JSONB,
  next_practice JSONB,
  raw_result JSONB,
  failure_code VARCHAR(100),
  generated_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (
     (source = 'LIVE_CAPTURE' AND session_id IS NOT NULL AND upload_id IS NULL)
     OR
     (source = 'UPLOADED_VIDEO' AND session_id IS NULL AND upload_id IS NOT NULL)
  )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_analyses_session
  ON analyses(session_id) WHERE session_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_analyses_upload
  ON analyses(upload_id) WHERE upload_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_analyses_user_source_created
  ON analyses(user_id, source, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_analyses_expires
  ON analyses(expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS analysis_scores (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  analysis_id UUID NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
  category VARCHAR(40) NOT NULL,
  status VARCHAR(20) NOT NULL
     CHECK (status IN ('AVAILABLE', 'UNAVAILABLE', 'NOT_SUPPORTED')),
  score NUMERIC(5,2) CHECK (score IS NULL OR score BETWEEN 0 AND 100),
  confidence NUMERIC(4,3) CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
  reason_code VARCHAR(100),
  detail JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (analysis_id, category)
);

CREATE TABLE IF NOT EXISTS analysis_feedbacks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  analysis_id UUID NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
  start_ms INTEGER NOT NULL CHECK (start_ms >= 0),
  end_ms INTEGER NOT NULL CHECK (end_ms > start_ms),
  category VARCHAR(40) NOT NULL,
  subcategory VARCHAR(40) NOT NULL,
  kind VARCHAR(20) NOT NULL CHECK (kind IN ('STRENGTH', 'IMPROVEMENT')),
  severity VARCHAR(20) NOT NULL CHECK (severity IN ('INFO', 'WARNING')),
  message VARCHAR(500) NOT NULL,
  suggestion VARCHAR(500),
  detail JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (
     (category = 'EXPRESSION' AND subcategory = 'FACIAL_EXPRESSION')
     OR (category = 'GAZE' AND subcategory = 'GAZE_HOLD')
     OR (category = 'VOICE' AND subcategory IN ('SPEECH_RATE', 'VOLUME', 'INTONATION'))
     OR (category = 'EMOTION' AND subcategory IN ('EMOTION_EXPRESSION', 'EMOTION_TRANSITION'))
     OR (category = 'SCRIPT_DELIVERY' AND subcategory = 'SCRIPT_ACCURACY')
     OR (category = 'SITUATION_FIT' AND subcategory = 'SITUATION_FIT')
  )
);

CREATE INDEX IF NOT EXISTS idx_analysis_feedbacks_timeline
  ON analysis_feedbacks(analysis_id, start_ms);

CREATE INDEX IF NOT EXISTS idx_analysis_feedbacks_category
  ON analysis_feedbacks(category, subcategory, kind);
