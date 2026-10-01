# A05 로그아웃

`POST /api/auth/logout`은 본문 없이 A01의 세션·CSRF 헤더와 `refreshToken` 쿠키를 받습니다.
Bearer 인증은 요구하지 않으며 기존 Spring Security CSRF 검증은 유지합니다.
CSRF 누락·불일치는 `403 CSRF_INVALID`입니다.

성공 응답은 항상 다음과 같습니다. Access Token을 발급하지 않습니다.

```json
{"success":true,"data":{"accepted":true},"message":"OK","error":null}
```

쿠키가 없거나 빈 값이면 Repository를 호출하지 않습니다. 값이 있으면 기존
`RefreshTokenGenerator.hash()`로 SHA-256 해시를 생성하고 A04와 동일한
`lockOwnerByTokenHash()`로 사용자 행의 비관적 쓰기 잠금을 획득합니다.
DB에 토큰이 없으면 그대로 성공하며, 있으면 만료·폐기·계정 상태와 무관하게
같은 family의 `revoked_at`이 null인 모든 토큰에 기존 `revoke()`를 적용합니다.
이미 폐기된 토큰의 폐기 시각과 교체 링크는 보존하고 다른 family는 변경하지 않습니다.

사용자 행 잠금과 family 조회·갱신은 하나의 `@Transactional` 안에서 실행합니다.
Refresh가 먼저 커밋하면 Logout은 새 후속 토큰까지 폐기합니다.
Logout이 먼저 커밋하면 Refresh는 폐기된 토큰을 보고 재발급하지 않습니다.
이전 세대 쿠키로 Logout을 호출해도 현재 세대 토큰이 남지 않도록 family 전체를 폐기합니다.
DB 실패는 성공으로 숨기지 않고 공통 500 오류로 처리하며 전체 변경을 롤백합니다.

트랜잭션 성공 후 Controller는 로그인·재발급과 공유하는 쿠키 생성 메서드로
`refreshToken=`, `Max-Age=0`, `Path=/api/auth`, `HttpOnly`, `SameSite=Lax`를 반환합니다.
Secure는 기존 `auth.refresh-cookie.secure` 설정을 따릅니다.
쿠키가 원래 없어도 삭제 쿠키를 반환하며, DB 실패나 CSRF 실패에는 반환하지 않습니다.
토큰 원문은 JSON이나 애플리케이션 로그에 출력하지 않습니다.

테스트는 A01 세션 요청, 전체 family 폐기, 다른 family 보존, 쿠키 누락·빈 값·미등록·
폐기·만료, 삭제 쿠키 정책, CSRF, refresh/logout 경합, DB 제약 오류 시 전체 롤백을 검증합니다.
H2 PostgreSQL 호환 모드를 사용하며 실제 PostgreSQL의 잠금 동작 검증은 별도입니다.

Access JWT는 서버에 저장하지 않으므로 개별 JWT 즉시 폐기를 추가하지 않았습니다.
기발급 Access JWT는 자체 만료 시각까지 유효합니다.
A06 내 정보 조회와 Bearer 인증 필터는 후속 작업입니다.
