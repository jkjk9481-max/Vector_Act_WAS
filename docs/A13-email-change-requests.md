# A13 이메일 변경 요청

`POST /api/users/me/email-change-requests`는 Bearer Access Token이 필요합니다.
기존 Bearer 전용 회원 API와 동일하게 이 POST 경로만 CSRF 검사에서 제외합니다.

```json
{"newEmail":"new-actor@example.com","currentPassword":"example-password-2026"}
```

성공 시 `202 Accepted`와 기존 공통 응답을 반환합니다.

```json
{"success":true,"data":{"accepted":true},"message":"OK","error":null}
```

Controller → EmailChangeRequestService → UserRepository / AuthOneTimeTokenRepository 구조입니다.
회원 행을 잠그고 ACTIVE 여부와 기존 BCrypt 비밀번호를 검증합니다.
새 이메일은 기존 회원가입과 같이 앞뒤 공백 제거 및 소문자 정규화 후 검증합니다.
필수 이메일 형식과 최대 254자를 확인하고, 현재 주소와 같으면 `400 VALIDATION_ERROR`,
이미 users에 등록된 주소이면 `409 EMAIL_ALREADY_EXISTS`입니다.
탈퇴 계정의 주소도 기존 회원가입의 중복 검사 정책대로 사용 중인 주소로 처리합니다.
현재 비밀번호 누락/빈 문자열은 `400 VALIDATION_ERROR`, 불일치 또는 UTF-8 72바이트 초과는
`403 CURRENT_PASSWORD_INVALID`입니다. 비밀번호 입력은 정규화하지 않습니다.

동일 회원의 미사용 EMAIL_CHANGE 토큰만 used_at을 기록해 무효화하고 새 토큰을 저장합니다.
이전 무효화와 새 저장은 같은 트랜잭션이므로 생성·저장·커밋 실패 시 이전 토큰도 유지됩니다.
동일 회원의 동시 요청도 회원 잠금으로 직렬화합니다. 다른 회원이나 REAUTH/PASSWORD_RESET은 유지합니다.
새 토큰은 기존 256비트 난수 생성기와 SHA-256 해시를 재사용하며 원문은 DB·응답·로그에 저장하지 않습니다.
token_type=EMAIL_CHANGE, new_email=정규화한 새 주소, expires_at=created_at+15분, used_at=null입니다.
이메일·이름·비밀번호·기존 Refresh Token은 변경하지 않습니다. A14 엔드포인트나 이메일 확정 처리는 없습니다.

기존 토큰 Repository의 조건부 소비는 소유자·용도·미사용·만료를 검사하지만,
A13은 새 토큰을 소비하지 않고 발급만 합니다. 다른 회원의 대기 요청을 주소 예약으로 취급하지 않습니다.
실제 이메일 변경 시 중복 주소 재검사는 후속 완료 API의 별도 작업 범위입니다.

## 이메일 연동 및 운영 DB

현재 SMTP/메일 API가 없으므로 `EmailChangeDelivery` 구현체를 Spring Bean으로 연결해야 합니다.
`EmailChangeDeliveryListener`가 커밋 성공 후에만 새 주소와 원문 토큰을 전달합니다.
발송 구현체는 신뢰할 수 있는 설정 URL로 링크를 만들고 네트워크 타임아웃을 설정해야 합니다.
미연결 또는 발송 실패 시 토큰은 저장된 상태이고 202는 접수 의미입니다.
로그는 발송 상태와 예외 종류만 남깁니다. 현재 메모리 이벤트이므로 영속 큐·자동 재시도는 없습니다.

운영은 ddl-auto=none이므로 기존 A09 DDL이 적용된 DB에 `docs/sql/A13_email_change_tokens.sql`을
배포 전에 적용해야 합니다. new_email 컬럼과 EMAIL_CHANGE 허용 제약을 추가합니다.
이 SQL은 앱이 자동 실행하지 않으며 이번 작업에서 운영 DB에 실행하지 않았습니다.

## 요청 제한 및 검증

기존 LoginRateLimiter를 별도 인스턴스로 재사용해 회원별 기본 15분 10회로 제한합니다.
유효한 형식의 요청부터 비밀번호 검증 전에 집계하며 초과 시 `429 RATE_LIMITED`입니다.
`EMAIL_CHANGE_MAX_ATTEMPTS`, `EMAIL_CHANGE_WINDOW_SECONDS`로 조정합니다.
다른 API와 카운터가 섞이지 않으며 단일 서버 메모리 기반입니다.
횟수 기준은 명세에 없어 설정 가능한 기본값으로 정했습니다.
공통 Bearer 오류 및 비활성 회원 404 정책은 유지하고 DB 연결 실패는 503, 기타 저장 실패는 500입니다.

EmailChangeRequestIntegrationTests는 실제 인증 필터·BCrypt·트랜잭션·H2 DB를 사용하고 메일만 모의합니다.
정상 발급, 입력 오류, 비밀번호 불일치, 중복/동일 이메일, 인증, 요청 제한,
재요청 무효화 범위, 동시 요청, 저장 실패 롤백, 커밋 후 전달 및 계정 정보 유지 등을 검증합니다.
전체 테스트는 `.\gradlew.bat test --no-daemon`으로 실행합니다.
실제 메일 배달 및 운영 PostgreSQL 환경 검증은 별도입니다.
