# A10 회원 탈퇴 — FR-AUTH-07

회원 탈퇴는 두 단계입니다. 요청을 받은 날에는 계정 상태를 바꾸고 접근을 막습니다.
7일이 지나면 스케줄러가 영상 저장소를 지운 뒤 DB 데이터를 지웁니다.
commit/push 또는 운영 DB 변경은 이 구현에 포함하지 않습니다.

## 요청과 응답

`DELETE /api/users/me`

- `Authorization: Bearer <Access Token>`
- `X-Reauth-Token: <A09에서 발급받은 토큰>`
- 요청 본문 없음. 본문을 보내면 400입니다.
- 명시적인 Bearer 인증을 사용하므로 이 API는 CSRF 토큰을 요구하지 않습니다.

성공 HTTP 상태는 **202 Accepted**이며 기존 공통 응답의 `data` 안에 다음 값이 들어갑니다.

```json
{
  "deletedAt": "2026-10-07T01:00:00Z",
  "purgeAt": "2026-10-14T01:00:00Z"
}
```

202는 탈퇴 상태 변경이 커밋되었고 영구 삭제는 나중에 진행된다는 뜻입니다.
시각은 UTC 기준이고 `purgeAt = deletedAt + 7일`입니다.
스케줄러는 기본 1시간 간격이므로 실제 삭제는 예정 시각 이후 첫 성공한 실행에서 이루어집니다.
응답에는 Refresh Token 쿠키 삭제와 캐시 저장 금지 헤더도 포함됩니다.

화면에서 처리 정책을 표시하고 최종 확인 후에만 이 API를 호출해야 합니다.
사용자가 취소하면 호출하지 않습니다. 백엔드만으로 정책 표시 여부를 확인할 수는 없습니다.

## 코드 읽는 순서

1. `WithdrawalController`: HTTP 헤더를 받고 본문 유무와 요청 횟수를 검사합니다.
2. `WithdrawalService`: 회원 행을 잠그고 재인증 토큰 검증 → Soft Delete → 토큰 폐기를 수행합니다.
3. `AccountStatusInterceptor`: 보호 MVC 요청마다 회원 상태를 조회해 기존 JWT 접근도 차단합니다.
4. `MemberPurgeScheduler`: 보관 기간이 지난 회원 ID를 조회하고 한 명씩 삭제를 요청합니다.
5. `MemberPurgeService`: 외부 저장소 → 추가 DB 데이터 → 토큰·동의 → 회원 순서로 삭제합니다.
6. `MemberDataEraser`: 영상 저장소와 영상·분석 테이블 삭제를 실제 모듈에 연결할 계약입니다.

### 트랜잭션과 잠금이 필요한 이유

트랜잭션은 여러 DB 변경을 모두 성공시키거나 모두 취소하는 단위입니다.
탈퇴 저장에 실패했는데 A09 토큰만 소비되거나 Refresh Token만 폐기되는 일을 방지합니다.
같은 회원 행에 쓰기 잠금을 걸어 로그인·재발급·재인증·비밀번호 변경·탈퇴가 서로 엇갈리지 않게 합니다.
A07 이름 변경도 같은 잠금을 사용해 탈퇴와 동시에 뒤늦게 수정되는 일을 막습니다.

A09에서 발급한 토큰은 REAUTH 용도, 소유 회원, 미사용, 만료 전 조건을 만족해야 합니다.
원문은 저장하지 않으며 SHA-256 해시로 비교합니다.
조건부 UPDATE 한 번으로 검사와 소비를 수행하므로 동시에 사용해도 한 번만 성공합니다.
A09의 기존 5분 유효 기간은 유지됩니다. 탈퇴 후 다른 일회용 토큰도 전부 사용 불가로 만듭니다.

Access JWT는 서명 문자열 자체를 서버에서 지울 수 없습니다.
따라서 매 보호 요청에서 DB 상태를 확인하고 WITHDRAWN이면 거절합니다.
이미 처리 중이던 요청을 강제로 중단하는 기능은 아니며, 별도 WebSocket 세션 구현은 현재 저장소에 없습니다.
향후 실시간 연결 기능을 추가할 때도 탈퇴 상태 검사와 기존 연결 종료를 연결해야 합니다.

## 오류

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 요청 본문이 있음 |
| 401 | AUTH_REQUIRED | Bearer 인증 없음 |
| 401 | ACCESS_EXPIRED | Access Token 만료 |
| 401 | ACCESS_INVALID | Access Token 형식·서명·용도 오류 |
| 403 | REAUTH_REQUIRED | 재인증 토큰 누락·만료·사용 완료·타인 소유·다른 용도 |
| 404 | RESOURCE_NOT_FOUND | 회원이 존재하지 않음 |
| 409 | ACCOUNT_DELETED | 유효한 Access Token으로 이미 탈퇴한 계정에 A10 재요청 |
| 429 | RATE_LIMITED | 회원별 기본 60초 10회 초과 |
| 500 | INTERNAL_ERROR | 예상치 못한 처리·저장 오류 |
| 503 | DEPENDENCY_UNAVAILABLE | DB 연결 또는 트랜잭션 시작 불가 |

중복 탈퇴는 시각을 연장하지 않습니다. 만료 JWT는 계정 상태 확인보다 먼저 401로 거절됩니다.
다른 보호 API의 탈퇴 회원 응답은 기존 동작과 같은 404이며, 로그인/재발급도 차단됩니다.
요청 제한은 기존 구현과 같은 단일 서버 메모리 방식입니다.

## 영구 삭제와 재시도

회원마다 별도 트랜잭션을 사용합니다. 외부 삭제가 실패하면 DB 삭제를 시작하지 않습니다.
외부 삭제 후 DB 삭제가 실패하면 DB 변경은 롤백되고 다음 실행에서 외부 삭제부터 재시도합니다.
따라서 외부 삭제 구현체는 이미 없는 파일 삭제도 성공으로 처리해야 합니다.
WITHDRAWN과 purge_at이 DB에 남아 있으므로 서버가 재시작되어도 삭제 대상을 다시 찾습니다.

성공 커밋 이후 `member_purge result=SUCCESS userId=...`,
실패 시 `member_purge result=RETRY userId=... errorType=...` 로그를 남깁니다.
운영에서 장기 이력이 필요하면 이 애플리케이션 로그를 수집·보관해야 합니다.
사용자 이메일, 비밀번호, 토큰 원문, 저장소 예외 메시지는 이 로그에 남기지 않습니다.

### 아직 연결에 필요한 정보

**현재 저장소에는 영상 버킷 클라이언트와 영상·분석 테이블이 없습니다.**
따라서 실제 버킷까지 영구 삭제하는 기능은 아직 연결되지 않았습니다.
`MemberDataEraser` 구현체가 없으면 스케줄러는 실패를 기록하고 DB를 보존합니다.
빈 구현체로 성공 처리하면 영상만 남을 수 있으므로 운영에서 그런 구현체를 등록하면 안 됩니다.

연결하려면 저장소 종류, 버킷, 회원별 파일 식별 방법, 영상·분석 테이블/외래 키 구조가 필요합니다.
이 정보를 바탕으로 하나의 Spring Bean이 다음 두 메서드를 구현해야 합니다.

- `deleteExternalData(userId)`: 해당 회원 파일을 모두 삭제합니다. 페이지 조회, 부분 실패, 버전 관리 버킷의 이전 버전까지 고려해야 합니다. 실패 시 예외를 던집니다.
- `deleteDatabaseData(userId)`: 영상·분석 등 추가 테이블을 자식부터 삭제합니다. 호출자의 트랜잭션에 참여하고 자체 커밋하지 않습니다.

현재 구현은 모든 만료 대상 ID를 한 번에 조회합니다. 대상이 매우 많아지면 별도 작업 큐나
커서 기반 배치로 확장해야 합니다. 외부 저장소 호출에는 구현체에서 타임아웃을 설정해야 합니다.

## 설정과 DB 적용

`docs/sql/A10_withdrawal.sql`은 운영 적용용으로 준비한 SQL이며 자동 실행하지 않습니다.
기존 users의 account_status에 ACTIVE/WITHDRAWN을 허용하는 구조가 전제입니다.
기존 A03/A09 토큰 테이블도 필요합니다. 운영 설정은 ddl-auto=none입니다.

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| WITHDRAWAL_PURGE_ENABLED | true | 스케줄러 사용 |
| WITHDRAWAL_PURGE_DELAY_MS | 3600000 | 실행 종료 후 다음 실행까지 대기 |
| WITHDRAWAL_PURGE_INITIAL_DELAY_MS | 60000 | 서버 시작 후 최초 실행까지 대기 |
| WITHDRAWAL_RATE_LIMIT_MAX_ATTEMPTS | 10 | 제한 구간 내 요청 수 |
| WITHDRAWAL_RATE_LIMIT_WINDOW_SECONDS | 60 | 제한 구간 길이 |

## 테스트

`WithdrawalIntegrationTests`는 실제 인증 필터와 H2 DB를 사용합니다.
외부 저장소만 Mockito로 대체하므로 실제 버킷 연결을 검증하는 테스트는 아닙니다.
정상 응답, 토큰 오류, 중복/동시 탈퇴, 즉시 접근 차단, DB 저장 실패 롤백,
7일 전 보관, 자식 데이터 삭제, 저장소 실패 보존, DB 삭제 실패 및 재시도를 검증합니다.
기존 A09 테스트는 5분 만료·해시 저장·동시 1회 소비·만료 경계를 검증합니다.

일반 실행: `.\\gradlew.bat test`

이번 환경에서는 기존 build 출력 파일 접근 거부로 기본 실행이 실패하여,
소스 설정 변경 없이 별도 출력/캐시 경로를 사용하는 init script로 실행합니다.
`build/a10-test.init.gradle` 내용:

```groovy
allprojects {
    layout.buildDirectory = file('build/a10-verification-final')
}
```

실행: `.\\gradlew.bat test --project-cache-dir build/a10-gradle-cache-final --init-script build/a10-test.init.gradle`

같은 접근 거부가 재발하면 출력 폴더와 project-cache-dir에 새로운 이름을 사용합니다.
init script와 테스트 산출물은 Git에서 제외되는 build 폴더에 있으며 소스 변경에 포함되지 않습니다.
