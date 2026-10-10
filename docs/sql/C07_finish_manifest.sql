-- C07 촬영 종료: 종료 선언 값 저장 컬럼
-- DB 설계서 3.6 coaching_sessions에는 마지막 청크 번호와 신고 길이를 저장할 컬럼이 없어 추가합니다.
-- "최초 종료 정보는 변경 불가"(API 명세서 C07)를 판단하려면 최초 선언 값을 보관해야 하기 때문입니다.
-- duration_ms는 "검증된 실제 길이"이므로 클라이언트 신고값과 섞지 않고 별도 컬럼에 둡니다.
-- 앱은 이 스크립트를 실행하지 않으므로 배포 전에 운영 DB에 적용합니다.

ALTER TABLE coaching_sessions
  ADD COLUMN IF NOT EXISTS declared_last_chunk_index INTEGER
     CHECK (declared_last_chunk_index IS NULL OR declared_last_chunk_index BETWEEN 0 AND 120);

ALTER TABLE coaching_sessions
  ADD COLUMN IF NOT EXISTS declared_duration_ms INTEGER
     CHECK (declared_duration_ms IS NULL OR declared_duration_ms BETWEEN 1 AND 600000);
