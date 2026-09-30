# A01 CSRF 발급과 요청 방법

저장소에 별도 A01 최종 명세가 없어 다음 계약으로 구현했습니다.
Spring Security의 기본 세션 기반 CSRF 저장소와 기본 마스킹 처리기를 사용합니다.
인증 여부와 관계없이 CSRF 토큰이 필요하며 회원가입·로그인에도 예외가 없습니다.

## 1. 먼저 토큰 받기

`GET /api/auth/csrf` → `200 OK`

```json
{
  "success": true,
  "data": {
    "token": "<CSRF 토큰>",
    "headerName": "X-CSRF-TOKEN"
  },
  "message": "OK",
  "error": null
}
```

서버는 검증값을 세션에 저장합니다. 브라우저는 발급 응답의 `JSESSIONID` 쿠키를 보관해야 합니다.
응답은 `Cache-Control: no-store`로 캐시하지 않습니다.
`JSESSIONID`는 HttpOnly, Secure, SameSite=Lax입니다.
로컬 HTTP 개발에서만 `CSRF_SESSION_COOKIE_SECURE=false`로 설정합니다.

## 2. 회원가입 또는 로그인 요청하기

`POST /api/auth/signup`, `POST /api/auth/login`에는 다음 두 가지가 모두 필요합니다.

- A01에서 받은 `data.token`을 `data.headerName` 이름의 요청 헤더로 전송
- A01에서 받은 것과 같은 세션 쿠키 전송

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
    [csrf.data.headerName]: csrf.data.token
  },
  body: JSON.stringify({ email, password })
});
```

프런트와 백엔드가 다른 출처라면 별도의 신뢰할 출처·CORS·쿠키 배포 정책이 필요합니다.
이번 변경은 임의의 출처에 자격 증명 전송을 허용하지 않습니다.

## 3. 실패 처리

CSRF 토큰 누락·불일치·다른 세션·만료된 세션은 Controller 실행 전에
`403 ACCESS_DENIED`와 기존 `ErrorResponse` 형식으로 차단합니다.
따라서 CSRF가 유효해야 회원가입의 입력 검증이나 로그인의 401/429 처리에 도달합니다.
세션이 사라지면 A01을 다시 호출해 토큰과 세션 쿠키를 함께 갱신합니다.

CSRF 세션은 로그인 인증 정보를 저장하는 세션이 아닙니다.
Access JWT와 Refresh Token의 기존 발급 정책은 그대로 유지합니다.

참고: [Spring Security CSRF 공식 문서](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
