-- D05/D06 주간·월간 기간 피드백 캐시: dashboard_summaries
-- DB 설계서 3.15 기준. 앱은 이 스크립트를 실행하지 않으므로 배포 전에 운영 DB에 적용합니다.
-- 이 PR(D05)은 이 테이블을 읽기만 하며, 행을 만들고 AI 서버에 생성을 요청하는 단계는 범위 밖입니다.

CREATE TABLE IF NOT EXISTS dashboard_summaries (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  period_type VARCHAR(10) NOT NULL
     CHECK (period_type IN ('WEEKLY', 'MONTHLY')),
  period_start DATE NOT NULL,
  period_end DATE NOT NULL CHECK (period_end >= period_start),
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
     CHECK (status IN ('PENDING', 'READY', 'FAILED', 'INSUFFICIENT_DATA')),
  summary TEXT,
  statistics_updated_at TIMESTAMPTZ NOT NULL,
  request_message_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, period_type, period_start),
  CHECK (status <> 'READY' OR summary IS NOT NULL)
);
