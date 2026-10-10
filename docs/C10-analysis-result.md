# C10 분석 결과 조회

`GET /api/coaching-sessions/{sessionId}/result` · 성공 200 · Bearer · 본문 없음

완료(`COMPLETED`) 또는 부분 완료(`PARTIAL`)된 실시간 세션의 분석 결과를 돌려줍니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/AnalysisResultController` | 엔드포인트, 지역 예외 처리 |
| `business/AnalysisResultService` | 소유 확인, 결과 상태 판단, 응답 조립 (읽기 전용) |
| `persistence/Analysis`, `AnalysisScore`, `AnalysisFeedback` (+ Repository) | `analyses`, `analysis_scores`, `analysis_feedbacks` 엔티티 (DB 설계서 3.11~3.13) |
| `dto/AnalysisResultResponse` | 응답 (`scores` 7개 항목, `segments` 등) |
| `ErrorCode` | `RESULT_NOT_READY`, `ANALYSIS_FAILED`(409) 추가 |
| `docs/sql/C10_analysis_tables.sql` | 운영 DB 적용용 DDL |

## 동작
- 세션의 분석 상태에 따라 응답합니다.
  - `COMPLETED` / `PARTIAL`: 결과 반환
  - 분석 행이 없음 / `QUEUED` / `PROCESSING` / `CANCELED`: `409 RESULT_NOT_READY`
  - `FAILED`: `409 ANALYSIS_FAILED`
- `scores`는 `expression`, `voice`, `gaze`, `posture`, `emotion`, `scriptDelivery`, `situationFit` 7개가 **항상** 존재합니다. DB에 행이 없는 항목은 `UNAVAILABLE`(점수 null), `posture`는 `NOT_SUPPORTED`로 채웁니다.
- 점수는 소수 1자리로 반올림합니다(DB는 소수 2자리 저장). 점수를 낼 수 없으면 `score`는 null, `reasonCode`에 사유 코드가 담깁니다.
- `segments`는 시작 시각 순으로 최대 300개, `strengths`/`improvements`/`nextPractice`는 저장된 문자열 배열입니다.
- 타인 소유·삭제된·없는 세션은 `404`, 경로의 `sessionId`가 UUID가 아니면 `400`.

## 명세에 없거나 정한 값 (확인 필요)
- **`CANCELED` 분석**: 결과가 생성되지 않으므로 `RESULT_NOT_READY`로 처리했습니다. `ANALYSIS_FAILED`로 볼 여지도 있습니다.
- **분석 데이터 생성 주체**: 이 API는 읽기만 합니다. `analyses`·점수·피드백을 만드는 분석 요청·결과 수신(AI 서버 연동)은 구현하지 않았으며, 엔티티의 `queued`/`complete`/`fail` 메서드는 그 단계가 사용합니다.
- **명세서 표 레이아웃**: C10 응답 표가 PDF에서 열이 어긋나 필드 조건을 문맥으로 복원했습니다(`schemaVersion`=1.0, `scores.*` 필드 등). 원문과 한 번 대조가 필요합니다.
- **`subcategory`**: DB 피드백에는 있으나 API 응답 필드에 없어 노출하지 않았습니다.
- **DB 설계서 `analyses.upload_id` FK**: `video_uploads`가 아직 없어 FK 없이 컬럼만 두었습니다(SQL 주석 참고).

## 테스트
`AnalysisResultIntegrationTests` (8건): 완료 결과와 7개 항목·반올림·시간순 피드백, PARTIAL, 분석 없음 409, 진행 중·취소 409, 실패 409, 타인·없는·삭제 세션 404, 경로 형식 오류, Bearer 필수.
