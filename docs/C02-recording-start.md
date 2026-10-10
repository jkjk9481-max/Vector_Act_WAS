# C02 촬영 시작

`POST /api/coaching-sessions/{sessionId}/start` · 성공 200 · Bearer

C01에서 준비한 세션(CREATED)의 촬영 시작을 접수하고 입력 영상 정보를 기록합니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `CoachingSessionController` | `start` 엔드포인트 추가 (C01과 같은 지역 예외 처리) |
| `CoachingSessionService.start` | MIME 확인, 소유·상태 확인, 상태 전이, 영상 정보 저장 |
| `CoachingSession.startRecording` | 상태 전이(`RECORDING`, `UPLOADING`, `startedAt`, `videoExpiresAt`=+30일) |
| `persistence/SessionVideo(Repository)` | `session_videos` 엔티티 (DB 설계서 3.8) |
| `dto/CoachingSessionStartRequest` | 요청 DTO (width 1~1920, height 1~1080, frameRate 1~30) |
| `ErrorCode` | `SESSION_STATE_CONFLICT`(409) 추가 |
| `SignupSecurityConfig` | 이 엔드포인트를 CSRF 제외 (Bearer 전용) |
| `docs/sql/C02_session_videos.sql` | 운영 DB 적용용 DDL (C01 SQL 이후 적용) |

## 처리 순서
1. 요청 검증: 필드 누락, width/height/frameRate 범위 → `400 VALIDATION_ERROR`
2. `mimeType`이 지원 형식이 아니면 `415 UNSUPPORTED_MEDIA_TYPE`
   - 지원: `video/webm;codecs=vp8,opus`, `video/mp4;codecs=avc1.42E01E,mp4a.40.2`
   - 대소문자·공백 차이는 같은 값으로 보며 소문자 정식 표기로 저장합니다.
3. 회원 행 잠금 후 세션 조회. 타인 소유·삭제된·없는 세션은 모두 `404 RESOURCE_NOT_FOUND`
4. 세션 상태가 `CREATED`가 아니면 `409 SESSION_STATE_CONFLICT`
5. 세션을 `RECORDING`으로 바꾸고 `session_videos` 행을 만듭니다(입력 MIME, 해상도, 프레임레이트, `expires_at`=시작+30일). 프레임레이트는 소수 둘째 자리로 반올림합니다.

## 명세에 없거나 문서끼리 다른 부분 (확인 필요)
- **멱등 키 불일치**: DB 설계서의 API별 사용 테이블 표는 C02가 `idempotency_keys`를 쓴다고 하지만, API 명세서 C02는 "인증·추가 헤더: Bearer"만 있고 멱등 키가 없습니다. API 명세서를 따라 멱등 키를 받지 않습니다. 그래서 성공 응답을 못 받고 재시도한 클라이언트는 `409 SESSION_STATE_CONFLICT`를 받습니다.
- **`videoStatus` 전이**: 촬영 시작 시 값이 명세에 없습니다. 촬영 중 Chunk를 올리는 단계(C04~)이므로 `UPLOADING`으로 두었습니다. 다르게 정해진다면 `CoachingSession.startRecording`만 바꾸면 됩니다.
- **오류 우선순위**: 입력 형식 오류 → 415 → 404 → 409 순으로 검사합니다. 명세에 순서가 없어 정한 값입니다.
- **요청 제한(429)**: C02 오류 목록에 없어 적용하지 않았습니다.

## 테스트
`CoachingSessionStartIntegrationTests` (9건): 정상 시작과 저장값(30일 만료 포함), MP4·소수 프레임레이트·정규화, 미지원 MIME 415, 숫자 범위·필드 누락, 경계값, 중복 시작·종료 상태 409, 타인·없는·삭제 세션 404, 경로 UUID 오류, Bearer 필수.
