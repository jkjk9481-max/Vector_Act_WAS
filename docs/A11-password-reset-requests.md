# A11 비밀번호 재설정 요청

A11은 비밀번호를 잊은 사용자의 요청을 받고 **재설정용 토큰을 발급하는 단계**입니다.
새 비밀번호를 입력받거나 변경하는 A12는 구현하지 않았습니다.

## 요청과 응답

`POST /api/auth/password-reset-requests`

1. 먼저 A01 `GET /api/auth/csrf`를 호출합니다.
2. A01에서 받은 세션 쿠키를 유지하고, 응답의 `headerName`에 해당하는 헤더에 `token`을 넣습니다.
3. 아래 JSON을 전송합니다. Bearer 인증은 필요하지 않습니다.

```json
{"email":"actor@example.com"}
```

정상 입력이면 가입·미가입·탈퇴 이메일 모두 HTTP **202**와 같은 본문을 반환합니다.

```json
{
  "success": true,
  "data": {"accepted": true},
  "message": "OK",
  "error": null
}
```

`accepted=true`는 요청을 처리했다는 뜻입니다. 가입되어 있다거나 이메일이 배달되었다는 뜻이 아닙니다.
회원 ID, 토큰 원문·해시, 토큰 만료 시각은 응답에 포함하지 않습니다.
입력은 기존 가입/로그인과 동일하게 양끝 공백을 제거하고 소문자로 바꾼 뒤 검사합니다.

## Controller → Service → Repository

```text
CSRF 필터
  → PasswordResetRequestController
      이메일 형식 검사, IP·이메일 요청 제한, 202 응답
    → PasswordResetRequestService
        회원 조회, ACTIVE 검사, 토큰 생성, 트랜잭션 관리
      → UserRepository.lockByEmail
      → AuthOneTimeTokenRepository.saveAndFlush
    → 커밋 성공 후 PasswordResetDeliveryListener
      → PasswordResetDelivery (실제 발송 연결 인터페이스)
```

Controller는 HTTP 관련 처리를 담당하고 Repository를 직접 호출하지 않습니다.
Service는 응답 객체나 쿠키를 만들지 않고 업무 규칙을 처리합니다.
Repository는 기존 회원 조회/토큰 저장 기능을 재사용합니다. A11 전용 Repository를 중복 생성하지 않습니다.

회원이 없거나 WITHDRAWN이면 토큰을 발급하지 않고 정상 종료합니다.
활성 회원이면 같은 회원 행을 잠가 탈퇴 처리와 발급이 엇갈리지 않게 합니다.
발급 후 탈퇴가 실행되면 A10의 일회용 토큰 폐기 대상에 포함됩니다.

## 저장되는 토큰

기존 `auth_one_time_tokens`를 그대로 사용하므로 새 테이블/컬럼은 필요하지 않습니다.
운영 DB에는 기존 A09 DDL 및 필요한 정렬 SQL이 적용되어 있어야 합니다.

| 컬럼 | 저장 내용 |
| --- | --- |
| id | 새 UUID |
| user_id | 실제 활성 회원 ID |
| token_type | PASSWORD_RESET |
| token_hash | 원문 대신 SHA-256 해시(64자), 기존 VARCHAR(255) 사용 |
| created_at | UTC 발급 시각 |
| expires_at | 발급 시각 + 15분 |
| used_at | null: 아직 사용하지 않음 |

원문은 256비트 난수를 Base64URL로 표현한 43자 문자열입니다.
비밀번호 해시나 기존 Refresh Token은 변경하지 않습니다.
REAUTH는 기존처럼 5분이며 PASSWORD_RESET과 서로 다른 용도입니다.
재설정 토큰으로 로그인하거나 A10 탈퇴 재인증을 대신할 수 없습니다.

반복 요청은 서로 다른 토큰을 발급합니다. 요청만으로 이전 토큰을 무효화하지 않습니다.
향후 A12에서는 PASSWORD_RESET 용도, 소유 회원 상태, 미사용, expires_at > 현재 시각을 검사하고,
비밀번호 변경과 1회 소비를 같은 트랜잭션으로 처리해야 합니다.
**A11은 사용 요청을 받지 않으므로 이번 변경에 A12의 검증·소비 엔드포인트는 없습니다.**

## 실제 이메일 발송 연결 상태

**현재 프로젝트에는 SMTP/메일 API 연결이 없습니다.**
이번에는 토큰 발급·해시 저장과 발송 인터페이스까지 구현했습니다.
`PasswordResetDelivery` 구현체가 없으면 실제 메일은 보내지 않으며
`password_reset_delivery result=NOT_CONFIGURED`를 기록합니다.
원문을 응답이나 로그에 노출하는 개발용 우회 기능은 만들지 않았습니다.
따라서 실제 이메일 인증 절차를 완성하려면 발송 구현체와 신뢰할 수 있는 재설정 화면 URL이 필요합니다.

발송 이벤트는 DB 커밋 후에만 처리됩니다. INSERT나 커밋 전 작업이 실패하면 발송하지 않습니다.
커밋 후 발송 실패는 로그에 기록하고 같은 202를 반환합니다.
가입 이메일에서만 503을 반환해 가입 사실을 노출하지 않기 위한 처리입니다.

현재 이벤트는 **동기식 메모리 이벤트**입니다. 영속 발송 큐나 자동 재시도는 없습니다.
프로세스가 커밋 직후 종료되거나 발송이 실패하면 해당 메일이 유실될 수 있어 사용자의 재요청이 필요합니다.
발송 구현체에는 네트워크 타임아웃을 설정해야 합니다.
발송 보장/재시도가 필요하면 별도 큐나 암호화된 outbox 설계를 추가해야 합니다.
HTTP 상태/본문은 동일하지만 처리 시간을 일정하게 맞추는 기능은 아닙니다.

## 오류와 요청 제한

| HTTP | 코드 | 상황 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 필수 이메일 누락·형식/길이 오류·잘못된 JSON |
| 403 | CSRF_INVALID | 기존 공통 보안 정책에 따른 CSRF 누락·불일치 |
| 429 | RATE_LIMITED | 요청 횟수 초과 |
| 500 | INTERNAL_ERROR | 예상하지 못한 DB 저장/처리 실패 |
| 503 | DEPENDENCY_UNAVAILABLE | DB 연결 또는 트랜잭션 시작 불가 |

401 계열은 공통 오류 목록에 있지만 A11은 비로그인 공개 API이므로 Bearer 인증 오류를 발생시키지 않습니다.
Authorization 헤더가 있어도 이 API에서는 사용하지 않습니다.
미가입 이메일을 404로 응답하면 가입 여부가 드러나므로 항상 정상 202로 처리합니다.
CSRF 실패의 403은 기존 A01~A05와 같은 공통 필터 동작을 유지했습니다.

기본 제한은 **15분 동안 IP별 10회, 정규화한 이메일별 3회**입니다.
모두 회원 조회 전에 집계하므로 미가입 이메일도 같은 제한이 적용됩니다.
이메일은 카운터에 원문 대신 해시로 보관하고, 클라이언트가 보낸 X-Forwarded-For는 직접 신뢰하지 않습니다.
단일 서버 메모리 방식이므로 서버 재시작 시 초기화되고 여러 서버 사이에는 공유되지 않습니다.

| 환경 변수 | 기본값 |
| --- | --- |
| PASSWORD_RESET_IP_MAX_ATTEMPTS | 10 |
| PASSWORD_RESET_EMAIL_MAX_ATTEMPTS | 3 |
| PASSWORD_RESET_WINDOW_SECONDS | 900 |

## 테스트

`PasswordResetRequestIntegrationTests`는 실제 필터/트랜잭션/H2 DB와 모의 메일 발송을 사용합니다.
가입 여부별 응답 일치, A01 CSRF와 동일 세션, 입력 검증, Bearer 불필요,
15분 만료/해시 저장, 다른 용도 사용 거절, 요청 제한, 저장 실패와 롤백 시 미발송,
발송 실패 시 응답 일치, 기존 로그인 상태 유지를 확인합니다.
실제 SMTP 배달과 PostgreSQL 운영 환경을 검증한 테스트는 아닙니다.

일반 실행: `.\gradlew.bat test`

이 작업 환경에서는 기존 출력 폴더 접근 오류를 피하도록 별도 출력/캐시 경로를 사용합니다.
`build/a11-test.init.gradle`에 출력 경로를 지정하고 다음처럼 실행합니다.

```powershell
.\gradlew.bat test --project-cache-dir build/a11-gradle-cache --init-script build/a11-test.init.gradle
```

init script와 테스트 결과는 Git에서 제외되는 build 폴더에 있습니다.
운영 DB 실행, 실제 메일 발송, commit/push는 수행하지 않습니다.
