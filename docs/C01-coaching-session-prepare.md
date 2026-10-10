# C01 연습 세션 준비

`POST /api/coaching-sessions` · 성공 201 · Bearer + `Idempotency-Key`(UUID) 헤더

설정만 저장하고 실제 촬영은 C02에서 시작합니다. 회원당 활성 세션(CREATED/RECORDING/FINALIZING)은 1개입니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `domain/coaching/presentation/CoachingSessionController` | 엔드포인트, 헤더 파싱, 지역 예외 처리 |
| `domain/coaching/business/CoachingSessionService` | 검증, 멱등 처리, 활성 세션 검사, 저장 |
| `domain/coaching/persistence/CoachingSession(Repository)` | `coaching_sessions` 엔티티 (DB 설계서 3.6) |
| `global/idempotency/IdempotencyKey(Repository)` | `idempotency_keys` 엔티티 (DB 설계서 3.14, 이후 C02·U06 등이 같이 사용) |
| `global/config/SignupSecurityConfig` | `POST /api/coaching-sessions`를 CSRF 제외 (Bearer 전용) |
| `global/error/ErrorCode` | `COACHING_CONFIG_CONFLICT`(400), `ACTIVE_SESSION_EXISTS`(409), `IDEMPOTENCY_CONFLICT`(409) 추가 |
| `docs/sql/C01_coaching_sessions.sql` | 운영 DB 적용용 DDL (partial unique index 포함) |

## 처리 순서
1. 입력 검증: 대본 1~20000자, 상황 1~2000자(DB CHECK와 같은 코드포인트 기준), coaching 필드 필수, intensity 열거값 → 실패 시 `400 VALIDATION_ERROR`
2. `analysisOnly=true` 인데 `visualEnabled`/`voiceEnabled`가 하나라도 true → `400 COACHING_CONFIG_CONFLICT`
3. 회원 행 잠금(`lockById`)으로 같은 회원의 요청을 직렬화
4. 멱등 기록 조회 (회원, 키, 기능 범위 `COACHING_SESSION_CREATE`)
   - 본문 해시가 같으면 저장해 둔 응답을 그대로 반환(201, 세션을 새로 만들지 않음)
   - 본문 해시가 다르면 `409 IDEMPOTENCY_CONFLICT`
   - 만료된 기록이면 지우고 새 요청으로 처리
5. 활성 세션이 있으면 `409 ACTIVE_SESSION_EXISTS`
6. 세션과 멱등 기록을 한 트랜잭션에서 저장

## 응답 값
`status=CREATED`, `videoStatus=NOT_STARTED`, `analysisStatus=NOT_STARTED`, `analysisMode=REALTIME`(DB 기본값), `startedAt/endedAt/durationMs/videoExpiresAt=null`.

## 명세에 없어 임의로 정한 값 (확인 필요)
- **Idempotency-Key 누락·형식 오류**: 명세에 오류 코드가 없어 `400 VALIDATION_ERROR`로 처리했습니다.
- **멱등 기록 보관 기간**: DB 설계서가 "API 운영 정책"이라고만 해서 설정값 `idempotency.ttl-hours`(기본 24시간)로 두었습니다.
- **만료된 멱등 기록 정리**: 별도 스케줄러는 만들지 않았습니다. 같은 키가 다시 오면 그때 교체합니다. 정리 스케줄러가 필요한지 결정이 필요합니다.
- **요청 제한(429)**: C01 오류 목록에 없어 적용하지 않았습니다.
- **`analysisMode`**: 요청에 없고 명세상 REALTIME/NEAR_REALTIME/POST_ONLY 결정 규칙이 C01에 없어 DB 기본값 REALTIME을 사용합니다.

## 테스트
`CoachingSessionCreateIntegrationTests` (13건): 정상 생성과 초기 상태, 같은 키·같은 본문 재시도, 같은 키·다른 본문 충돌, 활성 세션 중복, 종료 세션 후 재생성, 회원별 키 범위, 만료된 멱등 기록 재사용, 설정 충돌, 입력 검증, 길이 경계, 헤더 누락·형식 오류, Bearer 필수.

H2에서는 partial unique index를 만들 수 없어 활성 세션 1개 제약은 Service 검사로만 검증했습니다. 운영 DB의 `uq_coaching_sessions_one_active`가 최종 방어선이며, 위반(SQLState 23505) 시에는 현재 일반 500으로 응답됩니다. 회원 행 잠금 때문에 정상 경로에서는 발생하지 않습니다.
