# A09 탈퇴 재인증

로그인한 회원의 현재 비밀번호를 다시 확인하고 탈퇴용 일회성 토큰을 발급합니다.
이 API만 호출해서 계정이 탈퇴되거나 로그인 토큰이 폐기되지는 않습니다.

## 요청과 응답

`POST /api/auth/reauth`

인증: `Authorization: Bearer <Access Token>`

```json
{"password":"현재 비밀번호"}
```

`password`는 필수 문자열이며 누락, null, 빈 문자열은 `400 VALIDATION_ERROR`입니다.
현재 비밀번호를 확인하는 요청이므로 새 비밀번호의 8~32자 규칙을 다시 적용하지 않습니다.
공백을 제거하지 않고 입력 그대로 BCrypt로 비교합니다. UTF-8 72바이트를 초과하면
BCrypt 비교를 실행하지 않고 `403 CURRENT_PASSWORD_INVALID`로 거절합니다.

성공 응답: `200 OK`

```json
{
  "success": true,
  "data": {
    "reauthToken": "예측할 수 없는 일회성 난수 토큰",
    "expiresAt": "2026-10-06T12:05:00Z"
  },
  "message": "OK",
  "error": null
}
```

`expiresAt`은 생성 시각에서 정확히 5분 뒤입니다. 토큰은 Access JWT와 다른 난수이며
Access Token이나 Refresh Token으로 대체 사용할 수 없습니다.
응답에 `Cache-Control: no-store`, `Pragma: no-cache`를 설정하고 로그인 쿠키는 변경하지 않습니다.
요청·서비스 결과·응답 DTO의 `toString()`은 비밀번호와 토큰 원문을 가립니다.

## 코드 읽는 순서

1. `SignupSecurityConfig`: 이 POST만 CSRF 검사에서 제외합니다. 공개 API 목록에는 넣지 않으므로 Bearer 인증은 필수입니다.
2. `AccessTokenAuthenticationFilter`: JWT를 검증하고 토큰 소유자의 UUID를 인증 정보에 저장합니다.
3. `ReauthRequest`: JSON 입력을 담고 Controller의 `@Valid`로 필수값을 검사합니다.
4. `ReauthController`: 인증된 UUID를 사용해 요청 횟수를 확인한 후 Service를 호출합니다.
5. `ReauthService.issue`: ACTIVE 회원을 잠금 조회하고 현재 비밀번호를 비교합니다.
6. `AuthOneTimeTokenRepository`: 난수 토큰의 SHA-256 해시와 소유자·용도·만료 시각을 저장합니다.
7. DB 커밋 성공 후 Controller가 토큰 원문과 만료 시각을 JSON으로 반환합니다.

Service의 `@Transactional`이 잠금 조회와 토큰 INSERT를 묶습니다. 저장이나 커밋 실패 시
발급을 성공으로 응답하지 않습니다. Controller와 Repository에는 별도 트랜잭션 선언이 필요 없습니다.
A08과 같은 사용자 행을 잠그므로 동시에 비밀번호를 변경할 때 이전 해시를 중간에 비교하지 않습니다.

## DB 테이블

운영 설정이 `ddl-auto: none`이므로 배포 시 [PostgreSQL DDL](sql/A09_auth_one_time_tokens.sql)을
별도로 적용해야 합니다. 이번 작업에서는 운영 DB에 실행하지 않았습니다.

`auth_one_time_tokens`에는 `id`, `user_id`, `purpose`, `token_hash`, `expires_at`, `used_at`,
`created_at`을 저장합니다. 명세에서 컬럼명이 생략된 용도 구분은 `purpose VARCHAR(30)`으로
정했으며 `PASSWORD_RESET`과 `REAUTH`를 사용합니다. 회원 소유권 확인을 위해 `user_id`를 둡니다.

`token_hash`는 명세대로 `VARCHAR(255) UNIQUE`이고 실제 SHA-256 해시는 64자입니다.
원문은 DB에 저장하지 않습니다. `created_at`의 DB 기본값은 `now()`입니다.
회원이 실제 삭제되면 외래 키의 `ON DELETE CASCADE`로 이 토큰들도 삭제됩니다.
Soft Delete는 회원 행 삭제가 아니므로 토큰 행은 그대로 남습니다.

## 1회 사용 보장과 향후 탈퇴 연결

`ReauthService.consumeForWithdrawal(userId, rawToken)`은 토큰을 실제로 사용할 때 호출합니다.
토큰을 발급하는 A09에서 즉시 used_at을 채우면 탈퇴 시 사용할 수 없으므로 발급 시에는 null입니다.

이 메서드는 `@Transactional(propagation = MANDATORY)`로 선언되어 있습니다.
향후 탈퇴 Service가 시작한 트랜잭션 안에서 호출해야 하며, 트랜잭션 없이 호출하면 거절합니다.
권장 연결 순서는 같은 트랜잭션 안에서 다음과 같습니다.

1. 인증된 회원 ID와 제출된 재인증 토큰으로 `consumeForWithdrawal`을 호출합니다.
2. 계정 상태, 탈퇴 시각, 7일 뒤 영구 삭제 예정 시각을 저장합니다.
3. 해당 회원의 로그인 토큰을 종료하고 커밋합니다.

소비 쿼리는 소유자, REAUTH 용도, `used_at IS NULL`, `expires_at > 현재 시각`을 모두
만족하는 행에만 used_at을 기록합니다. 검사와 사용 표시를 한 UPDATE로 처리하므로
동일 토큰을 동시에 제출해도 한 요청만 성공합니다. 정확히 만료 시각에 도달해도 거절합니다.
다른 회원, 만료, 이미 사용, 다른 용도, 잘못된 원문은 내부 소비 기능에서 `ACCESS_INVALID`로 거절합니다.
실제 탈퇴 저장이 실패하면 같은 트랜잭션의 used_at 변경도 롤백됩니다.
소비는 내부 Service 기능이며 이번에 별도의 소비 HTTP API는 만들지 않았습니다.

발급 재요청마다 별도의 토큰이 만들어지며, 각각 5분·1회 사용 규칙을 가집니다.
새 토큰 발급 시 이전 미사용 토큰을 폐기하는 정책은 명세에 없어 추가하지 않았습니다.

## 오류와 요청 제한

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 필수값 누락, 빈 비밀번호, JSON 오류 |
| 403 | CURRENT_PASSWORD_INVALID | 현재 비밀번호 불일치 |
| 401 | AUTH_REQUIRED / ACCESS_EXPIRED / ACCESS_INVALID | Bearer 인증 실패 |
| 404 | RESOURCE_NOT_FOUND | 회원 없음 또는 WITHDRAWN 상태 |
| 429 | RATE_LIMITED | 회원별 요청 횟수 초과 |
| 500 | INTERNAL_ERROR | 예상하지 못한 저장·처리 실패 |
| 503 | DEPENDENCY_UNAVAILABLE | DB 연결 자원 또는 트랜잭션 시작 실패 |

요청 제한 기본값은 회원별 60초에 10회입니다. 형식 검증을 통과한 요청에 적용하며
로그인·A08 카운터와 분리합니다. `auth.reauth-rate-limit.max-attempts`와
`auth.reauth-rate-limit.window-seconds` 설정으로 바꿀 수 있습니다.
현재 제한기는 기존 프로젝트와 같은 단일 서버 메모리 방식입니다.

## 검증과 구현 범위

`ReauthIntegrationTests`는 발급, 해시 저장, 5분 만료, 계정 보존, 인증/입력 오류,
요청 제한, DB 오류, 1회 사용, 만료 경계, 타인·다른 용도 거절, 동시 소비 및 롤백을 확인합니다.
H2의 PostgreSQL 호환 모드로 실행하며 운영 PostgreSQL에서 잠금 검증은 별도입니다.

이번 A09는 재인증 발급과 안전한 일회성 소비 기능까지입니다. 실제 탈퇴 요청 API,
Soft Delete, 7일 후 영상 버킷·DB Hard Delete 스케줄러, 영구 삭제 결과 기록,
주의사항 표시 화면은 후속 탈퇴 구현에서 연결해야 합니다.
`PASSWORD_RESET` 용도는 테이블에 정의하지만 15분짜리 재설정 토큰 발급 API는 구현하지 않습니다.
