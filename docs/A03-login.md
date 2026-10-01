# A03 로그인 구현 안내

## 요청과 응답

`POST /api/auth/login`에 JSON으로 `email`, `password`를 보냅니다.
먼저 [A01](A01-csrf.md)을 호출해 같은 세션 쿠키와 `X-CSRF-TOKEN` 헤더를 함께 보내야 합니다.
CSRF가 없거나 잘못되면 로그인 처리 전에 `403 CSRF_INVALID`로 거절됩니다.
이메일은 회원가입과 동일하게 앞뒤 공백을 제거하고 소문자로 정규화합니다.
비밀번호는 공백을 포함한 원문 그대로 `PasswordEncoder.matches()`에 전달합니다.
로그인 요청에는 회원가입의 비밀번호 8~32자 정책을 다시 적용하지 않습니다.
단, BCrypt 최대 72바이트를 넘는 입력은 자르지 않고 인증 실패로 처리합니다.

성공하면 HTTP 200이며 기존 `ApiResponse` 형식을 사용합니다.

```json
{
  "success": true,
  "data": {
    "accessToken": "<Access JWT>",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "user": {
      "userId": "<UUID>",
      "name": "배우",
      "email": "actor@example.com",
      "createdAt": "<가입 시각>"
    }
  },
  "message": "OK",
  "error": null
}
```

없는 이메일, 틀린 비밀번호, WITHDRAWN 계정은 모두 `401 INVALID_CREDENTIALS`입니다.
이번 A03 명세에 맞춰 입력 누락·형식 오류·잘못된 JSON도 로그인에서만 같은 401로 반환합니다.
회원가입의 입력 검증 오류는 기존 `400 VALIDATION_ERROR`를 유지합니다.

## 코드를 읽는 순서

1. 보안 필터가 CSRF를 검사한 뒤 `LoginWebConfig`가 등록한 `LoginRateLimiter`가 로그인 요청 횟수를 검사합니다.
2. `LoginController`의 `@RequestBody`가 JSON을 `LoginRequest`로 변환하고 `@Valid`가 입력을 검사합니다.
3. `LoginService`가 기존 `UserRepository.findByEmail()`로 회원을 조회합니다.
4. 기존 BCrypt 인코더로 비밀번호를 비교하고 계정이 ACTIVE인지 확인합니다.
5. `AccessTokenProvider`가 유효시간 900초의 JWT를 서명합니다. Access Token은 DB에 저장하지 않습니다.
6. `RefreshTokenGenerator`가 32바이트 난수를 생성하고 SHA-256 해시로 바꿉니다.
7. `RefreshTokenRepository`가 해시·사용자·발급 시각·14일 후 만료 시각·새 family ID를 저장합니다.
8. Service 트랜잭션이 커밋되면 Controller가 Access Token은 JSON으로, Refresh Token 원문은 쿠키로 반환합니다.

`saveAndFlush()`는 DB에 SQL을 실행하지만 트랜잭션을 확정하지는 않습니다.
Service에서 예외가 발생하거나 커밋이 실패하면 성공 응답과 쿠키는 반환되지 않습니다.
Service 내부 결과에는 쿠키를 만들 원문이 있으므로 이를 그대로 JSON으로 응답하지 않습니다.
요청/결과 객체의 `toString()`도 비밀번호와 토큰을 출력하지 않도록 했습니다.

## JWT 설정

- `JWT_SECRET_BASE64`: 필수. 암호학적 난수 최소 32바이트를 Base64로 인코딩한 값입니다.
- `JWT_ISSUER`: 기본 `vector-act`. 발급자와 검증자가 같은 값을 사용해야 합니다.
- 비밀키가 없거나 Base64 형식이 아니거나 32바이트보다 짧으면 애플리케이션 시작이 실패합니다.
- 실제 비밀키를 저장소에 넣지 말고 실행 환경의 비밀 설정으로 전달합니다.
- 테스트에서는 매번 난수 키를 생성하며 운영 키를 사용하지 않습니다.

JWT에는 회원 UUID(`sub`), 발급자(`iss`), 발급·만료 시각(`iat`, `exp`),
토큰 식별자(`jti`), 용도(`token_use=access`)만 포함합니다.
HS256 서명과 만료, 발급자, 용도, UUID, 최대 900초 수명을 검증합니다.
`AccessTokenProvider.verify()`는 검증 도구이며 아직 보호 API의 Bearer 인증 필터와 연결하지 않았습니다.

## Refresh Token 쿠키와 DB

쿠키 이름은 `refreshToken`입니다. `HttpOnly`, `SameSite=Lax`, 경로 `/api/auth`,
최대 수명 14일이며 Domain을 지정하지 않은 호스트 전용 쿠키입니다.
`REFRESH_COOKIE_SECURE` 기본값은 `true`입니다. 로컬 HTTP 개발에서만 `false`로 설정합니다.
토큰 응답은 `Cache-Control: no-store`와 `Pragma: no-cache`를 포함합니다.

운영 설정은 `ddl-auto: none`이므로 기존 users 테이블이 준비된 DB에
[`sql/A03_refresh_tokens.sql`](sql/A03_refresh_tokens.sql)을 배포 절차로 적용해야 합니다.
파일을 추가하는 것만으로 운영 DB에 테이블이 생성되지는 않습니다.
신규 로그인 시 `revoked_at`, `replaced_by_token_id`는 null입니다.
`token_hash`는 SHA-256의 64자리 16진수이므로 엔티티와 SQL 모두 `VARCHAR(64)`이며 정확히 64자 CHECK 제약을 둡니다.
`user_id` FK에는 `ON DELETE CASCADE`를 적용해 사용자가 DB에서 실제 삭제될 때 토큰도 함께 삭제합니다.
WITHDRAWN으로 상태를 바꾸는 것만으로는 삭제되지 않습니다.
기존 생성 SQL을 이미 적용했다면 [보정 SQL](sql/A03_refresh_tokens_alignment.sql)을 사용합니다.
사용 중인 FK 이름을 별도로 바꾼 환경이라면 그 이름에 맞춰 보정해야 합니다.

## 요청 제한과 현재 범위

명시된 횟수 정책이 없어 기본값은 서버가 확인한 IP당 60초 창에서 10회입니다.
CSRF를 통과한 요청은 성공·실패·잘못된 본문 모두 횟수에 포함하며 초과 시 `429 RATE_LIMITED`를 반환합니다.
`LOGIN_RATE_LIMIT_MAX_ATTEMPTS`, `LOGIN_RATE_LIMIT_WINDOW_SECONDS`로 조정할 수 있습니다.
이 구현은 서버 메모리를 사용하므로 재시작 시 초기화되고 여러 서버 간 횟수를 공유하지 않습니다.
프록시 뒤에서는 신뢰할 프록시의 주소 전달 설정이 필요합니다. 임의의 `X-Forwarded-For`는 직접 신뢰하지 않습니다.

A04 Refresh 재발급·회전·재사용 탐지, A05 Logout·토큰 폐기,
보호 API의 Bearer 인증 필터는 이번 구현에 포함하지 않았습니다.
회원가입은 기존 코드를 재사용하며 AI 대본 기능은 변경하지 않았습니다.

## 검증

H2 PostgreSQL 호환 모드에서 로그인·회원가입 통합 테스트를 실행했습니다.
JWT 검증과 요청 제한 시간 경계·동시 요청은 단위 테스트로 확인했습니다.
실제 운영 PostgreSQL DDL 적용 및 브라우저의 HTTPS 쿠키 전달은 별도 환경 검증이 필요합니다.
