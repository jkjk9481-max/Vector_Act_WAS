# 🎬 Vector Act WAS — 백엔드 개발 가이드

> **이 문서는 프로젝트에 투입되는 개발자를 위한 아키텍처 가이드입니다.**  
> 코드를 작성하기 전에 반드시 읽어주세요.

## 기술 스택

| 항목 | 기술 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 4.1.x |
| ORM | Spring Data JPA |
| Database | PostgreSQL |
| Auth | Spring Security + JWT |
| Build | Gradle |

---

## 프로젝트 전체 구조

```
com.personalab.vectoract.vector_act_was/
├── VectorActWasApplication.java     ← Spring Boot 진입점
├── global/                          ← 🔧 전역 공통 모듈 (아래 상세 설명)
│   ├── common/response/             ← 통합 응답 포맷
│   └── error/                       ← 예외 처리 체계
└── domain/                          ← 📦 도메인별 모듈 (아래 상세 설명)
    ├── member/                      ← 회원 (레퍼런스 예시)
    ├── script/                      ← 대본 (추후 추가)
    ├── coaching/                    ← 코칭 (추후 추가)
    └── dashboard/                   ← 대시보드 (추후 추가)
```

---

# 🔧 Global 패키지 가이드

`global/` 패키지는 **모든 도메인이 공통으로 사용하는 인프라 코드**입니다.  
도메인 코드를 작성할 때 직접 수정할 일은 거의 없지만, **사용법은 반드시 알아야** 합니다.

## 통합 응답 포맷

클라이언트는 항상 아래 두 가지 JSON 구조 중 하나를 받습니다. **예외 없이 모든 API가 이 형태를 따릅니다.**

### ✅ 성공 응답 — `ApiResponse<T>`

| 필드 | 타입 | 설명 |
|------|------|------|
| `status` | `int` | HTTP 상태 코드 (200, 201 등) |
| `message` | `String` | 응답 메시지 ("OK", "Created" 등) |
| `data` | `T` | 응답 데이터 (없으면 필드 자체가 생략됨) |
| `timestamp` | `LocalDateTime` | 응답 시각 |

```json
{
  "status": 200,
  "message": "OK",
  "data": {
    "id": 1,
    "email": "actor@example.com",
    "nickname": "배우김",
    "role": "USER"
  },
  "timestamp": "2026-09-15T21:48:00"
}
```

**Controller에서 사용법:**
```java
// 데이터 포함 응답 (200)
return ResponseEntity.ok(ApiResponse.ok(data));

// 데이터 포함 + 커스텀 메시지 (200)
return ResponseEntity.ok(ApiResponse.ok(data, "회원 정보가 수정되었습니다."));

// 데이터 없는 응답 (200)
return ResponseEntity.ok(ApiResponse.ok());

// 리소스 생성 응답 (201)
return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(data));
```

### ❌ 에러 응답 — `ErrorResponse`

| 필드 | 타입 | 설명 |
|------|------|------|
| `status` | `int` | HTTP 상태 코드 (400, 404, 500 등) |
| `code` | `String` | 커스텀 에러 코드 (`ErrorCode` enum의 이름) |
| `message` | `String` | 사용자 친화적 에러 메시지 |
| `errors` | `List<FieldError>` | 필드 검증 에러 목록 (Validation 실패 시만) |
| `timestamp` | `LocalDateTime` | 에러 발생 시각 |

```json
{
  "status": 404,
  "code": "MEMBER_NOT_FOUND",
  "message": "존재하지 않는 회원입니다.",
  "errors": [],
  "timestamp": "2026-09-15T21:48:00"
}
```

> ⚠️ **에러 응답은 직접 만들 필요가 없습니다.** `BusinessException`을 던지면 `GlobalExceptionHandler`가 자동으로 `ErrorResponse`를 생성합니다.

---

## 예외 처리 체계

### `ErrorCode` — 에러 코드 추가하기

새 도메인을 만들 때 해당 도메인의 에러 코드를 `ErrorCode` enum에 추가하세요.

```java
// global/error/ErrorCode.java 에 추가
// 네이밍 규칙: {도메인}_{에러_설명}

// ========== Script (대본) ==========
SCRIPT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 대본입니다."),
SCRIPT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 존재하는 대본입니다."),
```

### `BusinessException` — 비즈니스 예외 던지기

Service 계층에서 비즈니스 규칙 위반 시 `BusinessException`을 던지세요.  
**try-catch로 직접 에러 응답을 만들지 마세요.** `GlobalExceptionHandler`가 처리합니다.

```java
// ✅ 올바른 사용법 — ErrorCode만 지정
throw new BusinessException(ErrorCode.SCRIPT_NOT_FOUND);
// → {"code": "SCRIPT_NOT_FOUND", "message": "존재하지 않는 대본입니다."}

// ✅ 커스텀 메시지가 필요한 경우
throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "대본 제목은 100자 이내여야 합니다.");
// → {"code": "INVALID_INPUT_VALUE", "message": "대본 제목은 100자 이내여야 합니다."}
```

```java
// ❌ 이렇게 하지 마세요
try {
    ...
} catch (Exception e) {
    return ResponseEntity.badRequest().body(new ErrorResponse(...)); // 금지!
}
```

### `GlobalExceptionHandler` — 자동 처리되는 예외 목록

아래 예외들은 `GlobalExceptionHandler`가 자동으로 캐치하여 적절한 `ErrorResponse`를 반환합니다.  
**개발자가 별도로 처리할 필요 없습니다.**

| 예외 | 상황 | 응답 코드 |
|------|------|----------|
| `BusinessException` | 비즈니스 로직 위반 | ErrorCode에 정의된 상태 |
| `MethodArgumentNotValidException` | `@Valid` 검증 실패 | 400 |
| `MethodArgumentTypeMismatchException` | 파라미터 타입 불일치 | 400 |
| `MissingServletRequestParameterException` | 필수 파라미터 누락 | 400 |
| `HttpRequestMethodNotSupportedException` | 지원하지 않는 HTTP 메서드 | 405 |
| `NoResourceFoundException` | 존재하지 않는 URL | 404 |
| `Exception` (기타) | 예상치 못한 서버 오류 | 500 |

### Global 레퍼런스 파일

| 파일 | 위치 | 역할 |
|------|------|------|
| `ApiResponse.java` | `global/common/response/` | 성공 응답 래핑 (ok, created, of) |
| `ErrorResponse.java` | `global/common/response/` | 에러 응답 포맷 + FieldError |
| `ErrorCode.java` | `global/error/` | 도메인별 에러 코드 정의 |
| `BusinessException.java` | `global/error/exception/` | 비즈니스 예외 기본 클래스 |
| `GlobalExceptionHandler.java` | `global/error/` | 전역 예외 → ErrorResponse 자동 변환 |

---

# 📦 Domain 패키지 가이드

> `member` 도메인은 **레퍼런스(예시)** 용도로 작성되었으며, 새로운 도메인을 만들 때 이 구조를 그대로 따라가면 됩니다.

---

## 도메인 디렉토리 구조 규칙

새로운 도메인(예: `script`, `coaching`, `dashboard`)을 추가할 때, 아래 3계층 구조를 반드시 따르세요.

```
domain/
└── {도메인명}/
    ├── presentation/          ← 프레젠테이션 계층 (외부 요청/응답)
    │   ├── {도메인}Controller.java
    │   └── dto/
    │       ├── {도메인}Response.java    ← 응답 DTO
    │       └── {도메인}Request.java     ← 요청 DTO (필요 시)
    ├── business/              ← 비즈니스 계층 (핵심 로직)
    │   └── {도메인}Service.java
    └── persistence/           ← 퍼시스턴스 계층 (DB 접근)
        ├── {도메인}.java              ← JPA Entity
        └── {도메인}Repository.java
```

---

## 계층별 역할과 규칙

### 1. `presentation/` — 프레젠테이션 계층

| 항목 | 설명 |
|------|------|
| **역할** | HTTP 요청 수신, 응답 반환만 담당. 비즈니스 로직 금지 |
| **Controller** | `@RestController` + `@RequestMapping("/api/v1/{도메인}")` |
| **DTO** | Java `record`로 작성. Entity를 직접 반환하지 말 것 |
| **응답 포맷** | 반드시 `ApiResponse.ok(data)` 또는 `ApiResponse.created(data)`로 감쌀 것 |

```java
// ✅ 올바른 예시
@GetMapping("/{id}")
public ResponseEntity<ApiResponse<ScriptResponse>> getScript(@PathVariable Long id) {
    ScriptResponse response = scriptService.getScript(id);
    return ResponseEntity.ok(ApiResponse.ok(response));
}
```

### 2. `business/` — 비즈니스 계층

| 항목 | 설명 |
|------|------|
| **역할** | 비즈니스 로직, 트랜잭션 관리, 유효성 검증 |
| **트랜잭션** | 클래스 레벨에 `@Transactional(readOnly = true)`, 쓰기 메서드에만 `@Transactional` 추가 |
| **예외 처리** | `throw new BusinessException(ErrorCode.XXX)` 형태로 던질 것 |
| **반환 타입** | Entity가 아닌 **DTO(Record)** 로 변환하여 반환 |

```java
// ✅ 올바른 예시
public ScriptResponse getScript(Long scriptId) {
    Script script = scriptRepository.findById(scriptId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SCRIPT_NOT_FOUND));
    return ScriptResponse.from(script);
}
```

### 3. `persistence/` — 퍼시스턴스 계층

| 항목 | 설명 |
|------|------|
| **Entity** | `@NoArgsConstructor(access = PROTECTED)` + `@Builder` 패턴 사용 |
| **Repository** | `JpaRepository<Entity, Long>` 상속 |
| **주의** | Entity에 비즈니스 로직을 넣지 말 것. 상태 변경 메서드(update~)만 허용 |

```java
// ✅ Entity 생성 — Builder 패턴
Script script = Script.builder()
        .title("햄릿 독백")
        .content("사느냐 죽느냐...")
        .build();
```

---

## 새 도메인 추가 체크리스트

새 도메인을 만들 때 아래 순서로 작업하세요.

- [ ] `domain/{도메인명}/` 디렉토리 생성
- [ ] **persistence**: Entity 클래스 작성 (`@Entity`, `@Builder`, Auditing 필드)
- [ ] **persistence**: Repository 인터페이스 작성 (`JpaRepository` 상속)
- [ ] **presentation/dto**: 응답/요청 DTO를 `record`로 작성 (Entity → DTO 변환은 `from()` 팩토리 메서드)
- [ ] **business**: Service 클래스 작성 (`@Service`, `@Transactional`)
- [ ] **presentation**: Controller 작성 (`@RestController`, `ApiResponse`로 응답 래핑)
- [ ] **global/error**: 해당 도메인의 에러 코드를 `ErrorCode` enum에 추가

---

## 의존 방향 (반드시 단방향)

```
presentation  →  business  →  persistence
     ↓               ↓
  ApiResponse    BusinessException
  (global)        (global)
```

- `presentation`은 `business`에만 의존합니다.
- `business`는 `persistence`에만 의존합니다.
- **역방향 의존 금지**: `persistence`가 `business`를, `business`가 `presentation`을 알면 안 됩니다.
- 도메인 간 직접 호출도 최소화하세요. 꼭 필요하다면 `business` 계층끼리만 호출합니다.

---

## 참고: `member` 도메인 레퍼런스 파일

| 파일 | 역할 |
|------|------|
| [`MemberController.java`](presentation/MemberController.java) | Controller 작성법, API 버저닝, 응답 래핑 예시 |
| [`MemberResponse.java`](presentation/dto/MemberResponse.java) | Record DTO, Entity→DTO 변환 `from()` 패턴 |
| [`MemberService.java`](business/MemberService.java) | 트랜잭션 설정, 예외 던지기, DTO 반환 패턴 |
| [`Member.java`](persistence/Member.java) | Entity 설계, Builder, 내부 Enum, Auditing |
| [`MemberRepository.java`](persistence/MemberRepository.java) | JpaRepository 상속, 쿼리 메서드 네이밍 |

> **코드를 작성하기 전에 위 파일들을 한번 읽어보면 전체 흐름이 바로 파악됩니다.**
