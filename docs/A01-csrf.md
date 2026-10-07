# A01 CSRF 발급과 요청 방법

최종 A01 명세에 따라 응답은 `csrfToken`과 `expiresAt`입니다.
Spring Security의 세션 저장소에 만료 검증을 추가하고 기본 마스킹 처리기를 유지합니다.
회원가입·로그인·재발급·로그아웃·A11에는 CSRF가 필요합니다. 기존 Bearer 전용 API의 CSRF 제외는 유지합니다.

## 1. 먼저 토큰 받기

`GET /api/auth/csrf` → `200 OK`

```json
{
  "success": true,
  "data": {
    "csrfToken": "<CSRF 토큰>",
    "expiresAt": "2026-10-07T01:30:00Z"
  },
  "message": "OK",
  "error": null
}
```

서버는 검증값을 세션에 저장합니다. 브라우저는 발급 응답의 `JSESSIONID` 쿠키를 보관해야 합니다.
`data.csrfToken`은 문자열, `data.expiresAt`은 UTC date-time 문자열입니다.
`data`에는 이 두 필드만 포함합니다. 기존 `token`과 `headerName`은 제거했습니다.
전송 헤더는 **X-CSRF-TOKEN**으로 고정합니다.

유효 기간의 명세 값은 별도로 제공되지 않아 기본 **30분**으로 설정했습니다.
환경 변수 `CSRF_TTL_SECONDS`로 초 단위 변경할 수 있습니다(양수만 허용).
토큰 저장 시 기록한 만료 시각을 응답하며, 같은 세션에서 A01을 다시 호출해도 만료 전에는 연장하지 않습니다.
서버는 현재 시각이 expiresAt 이상이면 기존 토큰을 거절합니다.
그 후 A01을 호출하면 새 토큰과 새 만료 시각을 받습니다.
세션이 먼저 만료되거나 삭제되면 expiresAt 이전에도 A01을 다시 호출해야 합니다.
이전 형식의 만료 정보 없는 세션도 새 토큰 발급이 필요합니다.
A01 자체에는 `RATE_LIMITED` 규칙을 적용하지 않습니다.
응답은 `Cache-Control: no-store`로 캐시하지 않습니다.
`JSESSIONID`는 HttpOnly, Secure, SameSite=Lax입니다.
로컬 HTTP 개발에서만 `CSRF_SESSION_COOKIE_SECURE=false`로 설정합니다.

## 2. 회원가입 또는 로그인 요청하기

`POST /api/auth/signup`, `POST /api/auth/login`, `POST /api/auth/password-reset-requests` 등에는 다음 두 가지가 모두 필요합니다.

- A01에서 받은 `data.csrfToken`을 `X-CSRF-TOKEN` 요청 헤더로 전송
- A01에서 받은 것과 같은 `JSESSIONID` 세션 쿠키를 CSRF 헤더와 함께 전송

같은 출처의 브라우저 예시입니다.

```javascript
const csrf = await fetch('/api/auth/csrf', {
  credentials: 'include'
}).then(response => response.json());

const response = await fetch('/api/auth/login', {
  method: 'POST',
  credentials: 'include',
  headers: {
    'Content-Type': 'application/json',
    'X-CSRF-TOKEN': csrf.data.csrfToken
  },
  body: JSON.stringify({ email, password })
});
```

프런트와 백엔드가 다른 출처라면 별도의 신뢰할 출처·CORS·쿠키 배포 정책이 필요합니다.
이번 변경은 임의의 출처에 자격 증명 전송을 허용하지 않습니다.

## 3. 실패 처리

CSRF 토큰 누락·불일치·토큰 만료·다른 세션·만료된 세션은 Controller 실행 전에
`403 CSRF_INVALID`와 기존 `ErrorResponse` 형식으로 차단합니다.
`CSRF_INVALID`는 CSRF 검증 실패 전용 코드이며, 일반 접근 권한 오류는 `ACCESS_DENIED`를 사용합니다.
따라서 CSRF가 유효해야 회원가입의 입력 검증이나 로그인의 401/429 처리에 도달합니다.
토큰이 만료되거나 세션이 사라지면 A01을 다시 호출해 토큰과 세션 쿠키를 함께 갱신합니다.

CSRF 세션은 로그인 인증 정보를 저장하는 세션이 아닙니다.
Access JWT와 Refresh Token의 기존 발급 정책은 그대로 유지합니다.

참고: [Spring Security CSRF 공식 문서](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
