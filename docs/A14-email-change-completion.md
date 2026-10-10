# A14 이메일 변경 완료

`POST /api/users/me/email-changes`는 A13이 새 이메일로 보낸 인증 링크의 토큰을 소비하고 이메일을 확정합니다.
인증 링크는 로그인하지 않은 브라우저에서도 열 수 있으므로 **Bearer 없이 CSRF만 요구**합니다.
(A01 `GET /api/auth/csrf`의 세션 쿠키와 `X-CSRF-TOKEN` 헤더가 필요합니다.)

```json
{"changeToken":"<email-change-token>"}
```

성공 시 `200 OK`와 기존 공통 응답을 반환합니다.

```json
{"success":true,"data":{"accepted":true},"message":"OK","error":null}
```

## 오류

| 상태 | 코드 | 상황 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | changeToken 누락·빈 문자열·255자 초과·JSON 형식 오류 |
| 400 | EMAIL_CHANGE_TOKEN_INVALID | 없는 토큰, 만료, 이미 사용, 다른 용도의 토큰, 탈퇴한 회원의 토큰 |
| 409 | EMAIL_ALREADY_EXISTS | A13 이후 다른 회원(탈퇴 회원 포함)이 같은 주소를 사용 중 |
| 429 | RATE_LIMITED | IP당 기본 60초 10회 초과 |
| 403 | CSRF_INVALID | CSRF 토큰 없음/불일치 |

토큰의 존재·만료·사용 여부는 모두 같은 `EMAIL_CHANGE_TOKEN_INVALID`로 응답해 상태를 구분할 수 없게 합니다.

## 구조

```text
CSRF 필터
  → EmailChangeCompletionController   검증, IP 요청 제한, 200 응답
    → EmailChangeCompletionService    하나의 트랜잭션
        → AuthOneTimeTokenRepository  findByTokenHash, consumeIfUsable
        → UserRepository              lockById, existsByEmail
        → RefreshTokenRepository      findByUserIdAndRevokedAtIsNull
```

처리 순서(모두 한 트랜잭션, 실패하면 전체 롤백):

1. 토큰 SHA-256 해시로 EMAIL_CHANGE 토큰 조회, 소유 회원 행 잠금, ACTIVE 확인
2. `consumeIfUsable` 조건부 UPDATE로 1회 소비(소유자·용도·미사용·만료 동시 검사)
3. `users.email` 중복 재검사 후 이메일 변경 (DB unique 위반도 409로 변환)
4. 회원의 미폐기 Refresh Token 전부 폐기 → 모든 기기에서 재로그인

409로 실패하면 트랜잭션이 롤백되어 토큰은 소비되지 않고 만료 전까지 남습니다.
같은 토큰으로 동시에 요청해도 한 번만 성공합니다.

## 변경하지 않는 것

- 비밀번호, 이름은 변경하지 않습니다.
- 이미 발급된 Access JWT는 만료 시각까지 유효합니다(A08과 같은 정책).
- 해당 회원의 다른 용도 토큰(REAUTH, PASSWORD_RESET)은 유지합니다. 명세에 없어 추가하지 않았습니다.
- 이메일 변경 완료 알림 발송은 하지 않습니다.

## 설정과 DB

- `EMAIL_CHANGE_COMPLETION_MAX_ATTEMPTS`(기본 10), `EMAIL_CHANGE_COMPLETION_WINDOW_SECONDS`(기본 60).
  횟수 기준은 명세에 없어 A12와 같은 값으로 정한 설정 가능한 기본값이며 단일 서버 메모리 기반입니다.
- 새 테이블/컬럼은 없습니다. A13의 `docs/sql/A13_email_change_tokens.sql`이 적용되어 있어야 합니다.

## 테스트

`EmailChangeCompletionIntegrationTests`가 실제 보안 필터·트랜잭션·H2 DB로 검증합니다.
정상 변경과 Refresh Token 전체 폐기(다른 회원 유지), 1회 사용, 없는/만료/다른 용도 토큰,
A13 재요청에 의한 이전 토큰 무효화, 탈퇴 회원, 주소 선점 시 409와 토큰 유지,
입력 오류, CSRF 필수/Bearer 불필요, 동시 요청 1회 성공을 포함합니다.
실행: `.\gradlew.bat test --no-daemon`
