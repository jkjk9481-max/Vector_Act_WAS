# A08 비밀번호 변경

`PATCH /api/users/me/password`는 `Authorization: Bearer <Access Token>`으로 인증합니다.
이 경로만 CSRF 검사에서 제외하며, 쿠키 인증 API의 기존 CSRF 정책은 유지합니다.
Controller → ChangePasswordService → UserRepository / RefreshTokenRepository 순서입니다.

```json
{
  "currentPassword": "current-password",
  "newPassword": "new-password",
  "newPasswordConfirm": "new-password"
}
```

세 필드 모두 필수입니다. 새 비밀번호는 8~32자이며, 기존 BCrypt 및 회원가입 정책과
같이 UTF-8 72바이트 이하입니다. 공백을 제거하거나 입력을 자르지 않습니다.
현재 비밀번호를 먼저 검증하고, 새 비밀번호와 확인 값의 일치를 검사합니다.
요청 객체의 toString과 입력 오류 응답에 비밀번호 원문을 포함하지 않습니다.

성공: `200 OK`

```json
{"success":true,"data":{"accepted":true},"message":"OK","error":null}
```

`newPasswordConfirm`, `accepted`는 명세 초안의 철자를 정리한 필드명입니다.
수정 대상은 Bearer 토큰의 UUID로만 결정하며 ACTIVE 회원만 허용합니다.
서비스의 쓰기 `@Transactional` 안에서 사용자 행을 잠근 뒤 비밀번호 해시와
updated_at을 갱신하고 해당 회원의 revoked_at이 null인 Refresh Token을 모두 폐기합니다.
이미 폐기된 시각과 교체 링크, 다른 회원의 토큰은 보존합니다.
저장 실패 시 비밀번호 변경과 토큰 폐기가 함께 롤백됩니다.
Controller와 Repository에는 별도 트랜잭션 선언 없이 서비스 트랜잭션을 사용합니다.

로그인도 같은 사용자 행을 잠그므로 이전 비밀번호로 진행 중인 로그인이 변경 후
활성 Refresh Token을 남기지 못합니다. 기존 재발급/로그아웃의 사용자 잠금과도 공유됩니다.
User의 DynamicUpdate는 동시 이름 수정이 이전 비밀번호 해시를 덮어쓰는 것을 방지합니다.
DB 스키마 추가는 없습니다.

성공 시 현재 브라우저의 Refresh Token 쿠키를 삭제하고 토큰을 새로 발급하지 않습니다.
모든 기기의 기존 Refresh Token은 재발급에 사용할 수 없으므로 다시 로그인해야 합니다.
단, 기존 Access JWT는 현재 설계대로 만료(발급 후 900초)까지 유효합니다.
Access Token의 즉시 무효화나 인증 버전 컬럼은 추가하지 않았습니다.

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 필수값, 길이, BCrypt 바이트 제한, JSON 오류 |
| 400 | PASSWORD_MISMATCH | 새 비밀번호 확인 불일치 |
| 403 | CURRENT_PASSWORD_INVALID | 현재 비밀번호 불일치 |
| 401 | AUTH_REQUIRED / ACCESS_EXPIRED / ACCESS_INVALID | 공통 Bearer 인증 실패 |
| 404 | RESOURCE_NOT_FOUND | 없는 회원 또는 WITHDRAWN 회원 |
| 429 | RATE_LIMITED | 회원별 변경 시도 횟수 초과 |
| 500 | INTERNAL_ERROR | 예상하지 못한 처리/저장 오류 |
| 503 | DEPENDENCY_UNAVAILABLE | DB 연결/트랜잭션 시작 불가 |

회원별 요청 제한 기본값은 60초에 10회입니다. 유효한 형식의 요청에 대해 현재
비밀번호 검증 전에 적용합니다. `auth.password-change-rate-limit.max-attempts`와
`auth.password-change-rate-limit.window-seconds`로 조정합니다. 기존 로그인 제한기를
별도 인스턴스로 사용하므로 로그인 카운터와 섞이지 않으며 단일 서버 메모리 기반입니다.

통합 테스트는 H2 PostgreSQL 호환 모드에서 실제 BCrypt, Bearer 인증, DB 저장과
토큰 폐기, 롤백, 동시 재발급/로그인을 확인합니다. 운영 PostgreSQL 잠금 검증은 별도입니다.
