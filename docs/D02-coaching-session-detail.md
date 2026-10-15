# D02 연습 기록 상세

`GET /api/coaching-sessions/{sessionId}` · 성공 200 · Bearer · 본문 없음

세션 하나의 전체 상세(C01~C09가 쓰는 상태값 + 저장된 대본·상황·코칭 설정)를 돌려주는 읽기 전용 API입니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/CoachingSessionController.get` | `{sessionId}` 단일 GET 엔드포인트 추가 |
| `business/CoachingSessionService.get` | 소유 확인 후 응답 변환(읽기 전용 트랜잭션) |
| `presentation/dto/CoachingSessionResponse` | 기존 C01·C02 응답 DTO를 그대로 재사용(명세 D02 필드와 완전히 일치) |

## 동작
- 응답은 C01·C02와 같은 형식(`CoachingSessionResponse`)입니다: `sessionId`, `status`, `videoStatus`, `analysisStatus`, `analysisMode`, `createdAt`, `startedAt`, `endedAt`, `durationMs`, `videoExpiresAt`, `scriptContent`, `situation`, `coaching{visualEnabled,voiceEnabled,analysisOnly,intensity}`.
- 세션 상태와 무관하게 소유자는 조회할 수 있습니다.
- 타인 소유·삭제된·없는 세션은 `404 RESOURCE_NOT_FOUND`, 경로의 `sessionId`가 UUID가 아니면 `400`.

## 테스트
`CoachingSessionDetailIntegrationTests` (5건): 준비 직후 전체 필드, 촬영 중 상태·만료 시각 반영, 타인·없는·삭제 세션 404, 경로 형식 오류, Bearer 필수.
