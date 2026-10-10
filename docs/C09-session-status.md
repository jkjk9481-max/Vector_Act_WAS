# C09 세션 상태 조회

`GET /api/coaching-sessions/{sessionId}/status` · 성공 200 · Bearer · 본문 없음

세션의 진행 상태(세션·영상·분석 상태, 누락 청크, 업로드 마감, 분석 시도, 실패 코드)를 돌려주는 읽기 전용 API입니다. 촬영 중 세션의 영상·분석 상태는 실시간 코칭 연결이 아니라 이 API로 조회합니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/CoachingSessionController` | `status` 엔드포인트 추가 |
| `business/SessionLifecycleService.get` | 소유 확인 후 진행 상태 응답 (읽기 전용 트랜잭션) |
| `business/SessionProgressMapper` | C07·C08과 같은 응답 변환 재사용 |

## 동작
- 응답은 C07·C08과 같은 형식(`SessionProgressResponse`)입니다: `sessionId`, `status`, `videoStatus`, `analysisStatus`, `analysisMode`, `missingChunkIndexes`, `uploadDeadlineAt`, `analysisAttempt`, `failureCode`.
- 종료 선언(C07) 전에는 `missingChunkIndexes`가 빈 배열, `uploadDeadlineAt`은 null입니다.
- 세션 상태와 무관하게 소유자는 조회할 수 있습니다.
- 타인 소유·삭제된·없는 세션은 `404 RESOURCE_NOT_FOUND`, 경로의 `sessionId`가 UUID가 아니면 `400`.

## 테스트
`SessionStatusIntegrationTests` (7건): 준비 직후 초기 상태, 촬영 중 상태, 종료 후 누락 번호와 마감, 실패 세션의 실패 코드·시도 횟수, 타인·없는·삭제 세션 404, 경로 형식 오류, Bearer 필수.
