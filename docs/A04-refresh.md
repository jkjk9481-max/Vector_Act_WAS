# A04 Access Token 재발급

`POST /api/auth/refresh`는 본문 없이 A01 세션과 `X-CSRF-TOKEN`,
`refreshToken` 쿠키를 받습니다. 성공 응답은 A03의 `LoginResponse`와 동일하며
Access JWT 수명은 900초입니다. Refresh Token 원문은 JSON에 포함하지 않습니다.

기존 `RefreshTokenGenerator.hash()`로 쿠키 원문을 해시하고 DB 토큰을 조회합니다.
누락·빈 값·없는 토큰은 `401 REFRESH_INVALID`, 만료 시각이 현재 이하이면
`401 REFRESH_EXPIRED`, 이미 폐기된 토큰은 `401 REFRESH_REUSED`입니다.
검사 순서는 만료, 폐기, 계정 상태 순입니다. 비활성 계정은 A03 정책과 동일하게
`401 INVALID_CREDENTIALS`로 거절합니다. CSRF 실패는 기존 필터의 `403 CSRF_INVALID`입니다.

정상 토큰은 새 난수와 해시를 생성하고 같은 family ID로 새 행을 저장합니다.
기존 행의 `revoked_at`과 `replaced_by_token_id`를 설정하고 JWT를 발급합니다.
모두 하나의 서비스 트랜잭션이며, 커밋 이후 Controller가 로그인과 공유하는 코드로
HttpOnly / Secure 설정값 / SameSite=Lax / Path=/api/auth / 14일 쿠키를 발급합니다.
저장·갱신·JWT 발급·커밋 실패 시 변경이 롤백되고 새 쿠키는 발급되지 않습니다.

재사용을 감지하면 동일 family의 아직 폐기되지 않은 행만 폐기합니다.
`ReusedTokenException`에 한해 `noRollbackFor`를 적용하므로 401을 반환하면서도
family 폐기는 커밋합니다. DB 오류는 이 예외에 해당하지 않으므로 롤백합니다.
다른 로그인 family에는 영향을 주지 않습니다.

토큰 조회 전에 사용자 행에 비관적 쓰기 잠금을 획득하여 동일 사용자의 refresh 요청을
직렬화합니다. 같은 토큰의 동시 회전뿐 아니라 이전 세대 재사용과 최신 세대 회전의
경합도 직렬화합니다. 잠금 범위는 사용자 단위지만 폐기 범위는 해당 family로 제한합니다.
기존 A03 테이블과 인덱스를 사용하므로 추가 DDL은 없습니다.

`RefreshIntegrationTests`는 실제 A01 세션, JWT와 쿠키, DB rotation, family 폐기 지속성,
다른 family 보존, 인증 오류, CSRF, 원문 비노출, 삽입·갱신 실패 롤백 및 동시 요청을 검증합니다.
테스트 DB는 H2 PostgreSQL 호환 모드이며 운영 PostgreSQL에서의 잠금 검증은 별도입니다.
A05 Logout과 Bearer 인증 필터는 후속 작업입니다.
