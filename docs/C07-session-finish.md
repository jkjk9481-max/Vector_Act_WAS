# C07 촬영 종료·최종화 접수

`POST /api/coaching-sessions/{sessionId}/finish` · 성공 202 · Bearer + `Idempotency-Key`(UUID)

촬영 종료를 접수하고 세션을 `FINALIZING`으로 바꿉니다. 누락된 청크 번호를 알려 주며, 마감(최초 접수 + 15분) 안에 C04·C05로 보완합니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/CoachingSessionController` | `finish` 엔드포인트 추가 (C01과 같은 멱등 키 헤더 처리) |
| `business/SessionFinishService` | 멱등 처리, 상태 전이, 종료 선언 검증 |
| `business/SessionProgressMapper` | 세션 → 진행 상태 응답 변환, 누락 번호 계산 (C08·C09 공유) |
| `persistence/CoachingSession.finish` | RECORDING → FINALIZING, `endedAt`, `uploadDeadlineAt`, 선언 값 기록 |
| `dto/SessionFinishRequest`, `SessionProgressResponse` | 요청(`lastChunkIndex`, `durationMs`), 응답 |
| `business/ChunkStatusService` | C06의 누락 범위를 선언한 `lastChunkIndex` 기준으로 확장 |
| `ErrorCode` | `FINISH_MANIFEST_CONFLICT`(409) 추가 |
| `docs/sql/C07_finish_manifest.sql` | `coaching_sessions`에 선언 값 컬럼 2개 추가 |

## 처리 순서
1. 입력 검증 → `400` (`lastChunkIndex` 0~120, `durationMs` 1~600000, `Idempotency-Key` 누락·형식 오류)
2. 회원 행 잠금 후 멱등 기록 확인: 같은 키·같은 본문이면 처음 응답 복원, 다르면 `409 IDEMPOTENCY_CONFLICT`
3. 세션 조회. 타인·삭제된·없는 세션은 `404`
4. 상태별 처리
   - `RECORDING`: 선언한 마지막 번호보다 큰 청크가 이미 있으면 `409 FINISH_MANIFEST_CONFLICT`, 아니면 `FINALIZING`으로 전이하고 선언 값·`endedAt`·`uploadDeadlineAt(+15분)` 기록
   - `FINALIZING`: 최초 선언과 같으면 그대로 응답(마감 시각은 변하지 않음), 다르면 `409 FINISH_MANIFEST_CONFLICT`
   - 그 외: `409 SESSION_STATE_CONFLICT`
5. 응답: 상태 값들, `missingChunkIndexes`(0 ~ 선언한 마지막 번호 중 VERIFIED가 아닌 번호), `uploadDeadlineAt`, `analysisAttempt`, `failureCode`

## 이 단계에서 하지 않는 것 (확인 필요)
- **영상 조립과 분석 요청**: 청크를 하나의 영상으로 조립하고 AI 서버에 분석을 요청하는 후속 처리는 구현하지 않았습니다. 조립 방식(예: ffmpeg)과 메시지 전달 경로가 문서에 확정되어 있지 않고, 명세의 C07은 "접수"(202)까지입니다. 그래서 `videoStatus`는 `UPLOADING`, `analysisStatus`는 `NOT_STARTED`로 남습니다.
- **마감 후 처리**: 마감(`uploadDeadlineAt`)이 지났는데도 누락이 있을 때의 세션 상태 전이(예: FAILED)는 규정이 없어 구현하지 않았습니다.

## 문서 보강이 필요한 부분
- **DB 설계서에 선언 값 컬럼이 없음**: "최초 종료 정보는 변경 불가"를 판단하려면 `lastChunkIndex`와 신고 길이를 저장해야 하지만 `coaching_sessions`에 해당 컬럼이 없습니다. `declared_last_chunk_index`, `declared_duration_ms`를 추가하는 `docs/sql/C07_finish_manifest.sql`을 만들었으니 운영 DB 적용과 DB 설계서 반영이 필요합니다. `duration_ms`는 "검증된 실제 길이"라 신고값과 섞지 않았습니다.
- **`FINISH_MANIFEST_CONFLICT` 범위**: 명세는 "최초 종료 정보 변경"만 설명합니다. 이미 예약된 청크가 선언한 마지막 번호를 넘는 경우도 같은 코드로 처리했습니다.

## 테스트
`SessionFinishIntegrationTests` (11건): 정상 접수와 누락 계산·DB 상태(마감 +15분), 전부 검증 시 빈 누락, 멱등 재시도 복원, 키 충돌, 새 키·같은 선언 재호출(마감 불변), 선언 변경 충돌, 선언 초과 청크 충돌, 상태 충돌, 입력·헤더 검증, 타인·없는·삭제 세션 404, Bearer 필수.
