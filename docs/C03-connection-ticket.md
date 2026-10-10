# C03 연결 티켓 발급

`POST /api/coaching-sessions/{sessionId}/connection-tickets` · 성공 201 · Bearer · 본문 없음

촬영 중인 세션의 소유자에게 AI 서버 WebSocket 접속용 일회용 티켓(JWT)을 발급합니다. DB 변경은 없습니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `domain/coaching/presentation/ConnectionTicketController` | 엔드포인트, 회원별 발급 횟수 제한, 지역 예외 처리 |
| `domain/coaching/business/ConnectionTicketService` | 소유·상태 확인, 설정 확인, 티켓 발급 호출 |
| `domain/coaching/business/ConnectionTicketSigner` | RS256 JWT 서명, 개인키 로딩 |
| `domain/coaching/presentation/dto/ConnectionTicketResponse` | 응답 DTO (ticket, expiresAt, webSocketUrl) |
| `global/config/SignupSecurityConfig` | 이 엔드포인트를 CSRF 제외 (Bearer 전용) |
| `application.yaml` | `coaching.connection-ticket.*` 설정 추가 |

## 티켓 규격 (AI 서버 설계서 7.2)
| 클레임 | 값 |
| --- | --- |
| `sub` | 회원 ID (세션 소유자) |
| `sid` | 세션 ID. AI 서버가 접속 경로의 `{sessionId}`와 일치하는지 확인 |
| `aud` | `ai-server` |
| `iat` / `exp` | 발급 시각 / 발급 + 30초 |
| `jti` | 티켓 고유 ID. AI 서버가 재사용을 막는 데 사용 (1회 사용) |

- 서명: 비대칭 RS256. 헤더 `kid`로 AI 서버가 공개키를 식별합니다.
- 응답의 `expiresAt`은 JWT의 `exp`와 같은 시각입니다.
- 클라이언트는 `{webSocketUrl}/ws/coaching-sessions/{sessionId}`로 연결하고 연결 후 5초 안에 `AUTH`로 티켓을 보냅니다. 재연결 때는 새 티켓을 발급받습니다.

## 오류
| 상황 | 응답 |
| --- | --- |
| 타인 소유·삭제된·없는 세션 | 404 `RESOURCE_NOT_FOUND` |
| 세션이 `RECORDING`이 아님 | 409 `SESSION_STATE_CONFLICT` |
| 서명 키 또는 접속 주소 미설정 | 503 `DEPENDENCY_UNAVAILABLE` |
| 발급 횟수 초과 | 429 `RATE_LIMITED` |
| `sessionId` 형식 오류 | 400 `VALIDATION_ERROR` |

## 설정 (환경변수)
| 환경변수 | 설명 |
| --- | --- |
| `COACHING_WS_TICKET_PRIVATE_KEY` | RSA 개인키(PKCS#8 PEM 전체). 줄바꿈은 `\n` 문자 그대로도 허용. 비면 C03은 503 |
| `COACHING_WS_TICKET_KEY_ID` | JWT `kid`. 비우면 공개키 지문(RFC 7638) 사용 |
| `COACHING_WS_URL` | 같은 도메인의 WSS 기본 주소(예: `wss://도메인`). 비면 C03은 503 |
| `COACHING_WS_TICKET_RATE_LIMIT_MAX_ATTEMPTS` / `_WINDOW_SECONDS` | 회원별 발급 제한(기본 20회/60초) |

AI 서버에는 개인키가 아니라 **공개키만** 배포합니다. 키 생성 예:
```
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out ticket-private.pem
openssl rsa -in ticket-private.pem -pubout -out ticket-public.pem
```

## 명세에 없어서 정한 값 (확인 필요)
- **허용 상태**: 명세는 상태 조건을 적지 않아 AI 서버 설계서 7장("촬영 중 연결·재연결")을 근거로 `RECORDING`에서만 발급합니다. 촬영 시작(C02) 전에 연결해야 한다면 `CREATED`도 허용해야 합니다.
- **서명 알고리즘**: 설계서는 RS256 또는 ES256입니다. RS256만 구현했습니다.
- **발급 횟수 제한 값**: 429만 명시되어 있어 20회/60초로 두었습니다(재연결을 고려해 로그인보다 느슨하게).
- **`iss` 클레임**: 설계서 표에 없어 넣지 않았습니다.
- **발급 이력**: 저장하지 않습니다. 1회 사용 보장은 AI 서버가 `jti`로 처리합니다.

## 테스트
- `ConnectionTicketIntegrationTests` (7건): 서명·클레임(공개키로 검증), 30초 유효와 `expiresAt` 일치, 티켓마다 다른 `jti`, 상태 409, 타인·없는·삭제 세션 404, 경로 UUID 오류, Bearer 필수, 발급 횟수 제한
- `ConnectionTicketUnconfiguredTests` (1건): 키·주소 미설정 시 503
