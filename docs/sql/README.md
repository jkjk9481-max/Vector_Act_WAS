# docs/sql 적용 순서

운영 DB는 `ddl-auto: none`이라 앱이 스키마를 자동으로 만들지 않습니다(테스트만 H2에서 엔티티로 즉석 생성). 이 폴더의 `.sql` 파일들을 **배포 전에 직접(psql 등으로) 운영 PostgreSQL에 적용**해야 실제 서비스가 동작합니다.

파일 이름의 `Axx`/`Cxx`/`Uxx`/`D05`/`S01`은 구현한 API 번호와 같지만, **적용 순서는 번호 순서가 아니라 테이블 의존 관계를 따라야 합니다.** 아래 순서대로 적용하세요. 대부분 파일은 `CREATE TABLE IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS` 등 방어적 구문을 써서 다시 실행해도 안전하지만, **`U01_video_uploads.sql`은 예외입니다** — 마지막 줄의 `ALTER TABLE analyses ADD CONSTRAINT fk_analyses_upload ...`에 `IF NOT EXISTS`가 없어 이미 적용된 DB에 두 번 실행하면 "제약이 이미 있다" 오류로 실패합니다(제약 하나만 빠진 거라 실제로는 문제 없는 상태). 전체를 처음부터 재실행할 상황이 아니라면 신경 쓰지 않아도 됩니다.

## 0. 확장(extension)과 기준 스키마(이 폴더에 파일 없음)

`users`, `user_consents`는 이 폴더에 생성 스크립트가 없습니다(이 `docs/sql` 관례가 생기기 전에 만들어진 기준 테이블). **DB가 비어 있다면 먼저 아래를 적용하세요** — `VectorAct_DB설계서_최종.pdf` 9장(PostgreSQL CREATE TABLE)의 내용을 그대로 옮긴 것입니다.

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto; -- gen_random_uuid() 사용. 이후 모든 파일이 이 확장에 의존합니다.

CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email VARCHAR(254) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  name VARCHAR(30) NOT NULL,
  profile_image_key VARCHAR(700) UNIQUE,
  profile_image_updated_at TIMESTAMPTZ,
  account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
     CHECK (account_status IN ('ACTIVE', 'WITHDRAWN')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMPTZ,
  purge_at TIMESTAMPTZ
);

CREATE TABLE user_consents (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  consent_type VARCHAR(20) NOT NULL
     CHECK (consent_type IN ('TERMS', 'PRIVACY')),
  version VARCHAR(32) NOT NULL,
  accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, consent_type, version)
);
```

`users`에 `profile_image_key`/`deleted_at`/`purge_at`가 이미 있으므로, 아래 2단계의 `A10_withdrawal.sql`/`A15_profile_image.sql`은 새 DB에서는 그냥 아무 일도 하지 않고 넘어갑니다(이미 있는 컬럼이라 `ADD COLUMN IF NOT EXISTS`가 건너뜀). 그래도 순서대로 실행해 두세요 — 인덱스까지는 안 만들어져 있습니다.

## 1. 순서대로 적용할 파일

| # | 파일 | 만드는 것 | 의존 | 비고 |
| - | --- | --- | --- | --- |
| 1 | `A03_refresh_tokens.sql` | `refresh_tokens` 테이블 | `users` | |
| 2 | `A03_refresh_tokens_alignment.sql` | `refresh_tokens` 제약 보정 | 1 | 1 바로 다음에 실행 |
| 3 | `A09_auth_one_time_tokens.sql` | `auth_one_time_tokens` 테이블 | `users` | |
| 4 | `A09_token_type_alignment.sql` | `purpose`→`token_type` 컬럼명 변경 | 3 | **새 DB에서는 건너뛰세요.** 3이 이미 `token_type`으로 만들어서, 이 파일은 옛 `purpose` 컬럼이 남아있는 DB에만 필요합니다(파일 자체 주석에도 명시) |
| 5 | `A13_email_change_tokens.sql` | `auth_one_time_tokens`에 `new_email` 추가, `token_type` CHECK에 `EMAIL_CHANGE` 포함 | 3 | 4를 건너뛰어도 안전(제약 이름을 `IF EXISTS`로 먼저 지움) |
| 6 | `A10_withdrawal.sql` | `users`에 `deleted_at`/`purge_at` + 탈퇴 인덱스 | `users` | 새 DB에서는 컬럼 추가는 건너뛰고 인덱스만 생성됨 |
| 7 | `A15_profile_image.sql` | `users`에 `profile_image_key`/`profile_image_updated_at` + UNIQUE 인덱스 | `users` | 6과 같은 이유로 인덱스만 생성됨 |
| 8 | `S01_script_jobs.sql` | `script_jobs` 테이블(OCR 작업) | `users` | 다른 도메인과 독립적 |
| 9 | `C01_coaching_sessions.sql` | `coaching_sessions`, `idempotency_keys` 테이블 | `users` | |
| 10 | `C02_session_videos.sql` | `session_videos` 테이블 | 9 | |
| 11 | `C04_session_chunks.sql` | `session_chunks` 테이블 | 9 | |
| 12 | `C07_finish_manifest.sql` | `coaching_sessions`에 종료 선언 컬럼 추가 | 9 | |
| 13 | `C10_analysis_tables.sql` | `analyses`, `analysis_scores`, `analysis_feedbacks` 테이블 | `users`, 9 | `analyses.upload_id`는 FK 없이 컬럼만 둠(14가 나중에 연결) |
| 14 | `U01_video_uploads.sql` | `video_uploads`, `video_upload_parts` 테이블 + `analyses.upload_id` FK 연결 | `users`, **13** | ⚠️ 번호상 U가 C보다 뒤지만, 파일 끝의 `ALTER TABLE analyses ADD CONSTRAINT fk_analyses_upload ...`가 `analyses` 테이블이 이미 있어야 성공합니다. **13을 먼저 적용하지 않으면 이 파일이 실패합니다(IF EXISTS 가드 없음).** |
| 15 | `D05_dashboard_summaries.sql` | `dashboard_summaries` 테이블(D05·D06 기간 피드백 캐시) | `users` | |

## 2. 적용 예시(psql)

```bash
psql "$DB_URL" -f docs/sql/A03_refresh_tokens.sql
psql "$DB_URL" -f docs/sql/A03_refresh_tokens_alignment.sql
psql "$DB_URL" -f docs/sql/A09_auth_one_time_tokens.sql
# A09_token_type_alignment.sql은 새 DB면 건너뛰어도 됩니다.
psql "$DB_URL" -f docs/sql/A13_email_change_tokens.sql
psql "$DB_URL" -f docs/sql/A10_withdrawal.sql
psql "$DB_URL" -f docs/sql/A15_profile_image.sql
psql "$DB_URL" -f docs/sql/S01_script_jobs.sql
psql "$DB_URL" -f docs/sql/C01_coaching_sessions.sql
psql "$DB_URL" -f docs/sql/C02_session_videos.sql
psql "$DB_URL" -f docs/sql/C04_session_chunks.sql
psql "$DB_URL" -f docs/sql/C07_finish_manifest.sql
psql "$DB_URL" -f docs/sql/C10_analysis_tables.sql
psql "$DB_URL" -f docs/sql/U01_video_uploads.sql
psql "$DB_URL" -f docs/sql/D05_dashboard_summaries.sql
```

## 3. 새 파일을 추가할 때

- API를 구현할 때 스키마가 바뀌면 `docs/sql/Axx_*.sql`(또는 해당 접두사)로 DDL을 추가하고, 이 표에 행을 추가하세요.
- 가능하면 `CREATE TABLE IF NOT EXISTS`/`ADD COLUMN IF NOT EXISTS`/`DROP CONSTRAINT IF EXISTS` 같은 방어적 구문을 써서, 이미 적용된 환경에 다시 돌려도 안전하게 만드세요(이 표 전체를 재실행해도 되게).
- 다른 테이블을 참조(FK)하면 이 표의 "의존" 칸에 적고, 참조 대상이 먼저 오도록 번호를 매기세요(14번처럼 파일 이름 접두사 순서와 실제 적용 순서가 다를 수 있습니다).
