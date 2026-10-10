# C11 실패한 분석 재접수

`POST /api/coaching-sessions/{sessionId}/analysis-retries` · 성공 202 · Bearer + `Idempotency-Key`(UUID) · 본문 없음

분석이 실패했고 원본 영상이 남아 있을 때만, 총 3회 시도 안에서 같은 분석을 다시 대기(`QUEUED`) 상태로 돌립니다. 재분석은 새 분석 행이 아니라 같은 행의 시도 번호(`attempt`)를 올리는 방식입니다(DB 설계서 3.11).

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/AnalysisRetryController` | 엔드포인트, 멱등 키 헤더 처리, 지역 예외 처리 |
| `business/AnalysisRetryService` | 멱등 처리, 재시도 가능 조건 판단, 상태 전이 |
| `persistence/Analysis.retry`, `CoachingSession.queueAnalysisRetry` | 시도 번호 증가와 대기 상태 전이 |
| `dto/AnalysisRetryResponse` | 응답 (`attempt`, `analysisStatus`) |
| `ErrorCode` | `ANALYSIS_ALREADY_RUNNING`, `RETRY_NOT_ALLOWED`(409), `VIDEO_EXPIRED`(410) 추가 |
| `SignupSecurityConfig` | 이 엔드포인트를 CSRF 제외 (Bearer 전용) |

## 처리 순서
1. 입력: `Idempotency-Key` 누락·형식 오류 → `400 VALIDATION_ERROR`
2. 회원 행 잠금 후 멱등 기록 확인: 같은 키·같은 세션이면 처음 응답 복원(시도 번호를 다시 올리지 않음), 다른 세션에 같은 키면 `409 IDEMPOTENCY_CONFLICT`
3. 세션 조회. 타인·삭제된·없는 세션은 `404`
4. 상태 판단
   - 분석이 `QUEUED`/`PROCESSING`: `409 ANALYSIS_ALREADY_RUNNING`
   - 영상이 `EXPIRED`/`DELETED`: `410 VIDEO_EXPIRED`
   - 분석이 `FAILED`가 아니거나, 영상이 `READY`가 아니거나, 시도가 이미 3회: `409 RETRY_NOT_ALLOWED`
5. 허용되면 `analyses.attempt`를 1 올리고 `QUEUED`로 전이, 세션의 `analysis_status=QUEUED`·`analysis_attempt` 갱신, 실패 코드 초기화

## 확인 필요
- **AI 서버 분석 요청 미구현**: 재접수까지만 하며 `ANALYSIS_REQUEST` 메시지 발행(전달 경로 미확정)은 하지 않았습니다. 상태가 `QUEUED`로 남아 있어도 실제 분석이 시작되지는 않습니다.
- **판단 순서**: 명세에 오류 우선순위가 없어 "진행 중 → 영상 만료 → 불가 조건" 순으로 정했습니다.
- **"총 3회" 해석**: 최초 분석을 1회로 보고 `attempt`가 3이면 더 요청할 수 없게 했습니다(최대 재요청 2번).
- **세션 상태(`status`)**: 재접수해도 바꾸지 않습니다. 분석 실패 후 세션 상태 전이 규정이 명세에 없습니다.
- **분석 행이 없는 세션**: 재시도할 대상이 없어 `RETRY_NOT_ALLOWED`로 처리했습니다.

## 테스트
`AnalysisRetryIntegrationTests` (12건): 정상 재접수와 DB 상태, 멱등 재시도, 다른 세션에 같은 키 충돌, 진행 중 409, 3회 상한, 실패가 아닌 상태 409, 영상 미준비 409, 분석 없음 409, 영상 만료 410, 헤더 검증, 타인·없는·삭제 세션 404, Bearer 필수.
