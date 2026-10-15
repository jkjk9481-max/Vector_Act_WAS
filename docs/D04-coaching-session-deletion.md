# D04 연습 기록 삭제

`DELETE /api/coaching-sessions/{sessionId}` · 성공 202 · Bearer · 본문 없음

종료 상태(COMPLETED/FAILED/CANCELED)의 연습 기록을 삭제 접수합니다. 활성 세션(CREATED/RECORDING/FINALIZING)은 C08(연습 취소)을 먼저 써야 합니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/CoachingSessionController.delete` | `DELETE /api/coaching-sessions/{sessionId}` 엔드포인트 추가 |
| `business/SessionLifecycleService.delete` | 활성 상태 검사 후 삭제 접수(읽기·쓰기 트랜잭션) |
| `persistence/CoachingSession.requestDeletion` | `deletionStatus=PENDING`, `deletedAt=now` 설정 |
| `presentation/dto/SessionDeletionResponse` (신규) | `deletionStatus`, `deletedAt` 응답 |
| `global/config/SignupSecurityConfig` | `DELETE /api/coaching-sessions/{sessionId}`를 CSRF 예외 목록에 추가(U05와 같은 패턴: Bearer 전용, 본문 없는 DELETE) |
| `global/error/ErrorCode` | 변경 없음(기존 `SESSION_STATE_CONFLICT`, `RESOURCE_NOT_FOUND` 재사용) |

## 동작
- 활성 세션(CREATED/RECORDING/FINALIZING)을 삭제하려 하면 `409 SESSION_STATE_CONFLICT`. 종료 상태만 삭제할 수 있습니다.
- 성공하면 `deletedAt`을 즉시 찍어 이후 조회(D01·D02)·통계(D05~D07)에서 바로 제외시킵니다(DB 설계서 6장: "요청 즉시 `coaching_sessions.deleted_at` 설정하여 조회·집계 제외").
- 원본 영상·청크 같은 자식 데이터의 실제 정리와 AI 서버 삭제 통보는 비동기이므로 `deletionStatus`는 항상 `PENDING`으로 응답합니다. 그 비동기 정리가 끝나야 `DONE`이 되는데, 그 정리 단계(스케줄러/AI 서버 통보)는 이번 구현 범위 밖입니다(C07 이후 조립 파이프라인과 마찬가지).
- 이미 삭제된·타인 소유·없는 세션은 모두 같은 `404 RESOURCE_NOT_FOUND`(반복 삭제 요청도 같은 404). 경로의 `sessionId`가 UUID가 아니면 `400`.

## 테스트
`SessionDeletionIntegrationTests` (7건): COMPLETED/FAILED/CANCELED 각 상태에서 삭제 성공, 삭제 후 D02 조회가 404로 막힘, 활성 상태(CREATED/RECORDING/FINALIZING) 409, 반복 삭제 404, 타인·없는·삭제 세션 404, 경로 형식 오류, Bearer 필수.
