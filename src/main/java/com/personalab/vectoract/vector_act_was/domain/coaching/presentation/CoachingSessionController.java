package com.personalab.vectoract.vector_act_was.domain.coaching.presentation;

import com.personalab.vectoract.vector_act_was.domain.coaching.business.CoachingSessionService;
import com.personalab.vectoract.vector_act_was.domain.coaching.business.SessionFinishService;
import com.personalab.vectoract.vector_act_was.domain.coaching.business.SessionLifecycleService;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.CoachingSessionCreateRequest;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.CoachingSessionResponse;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.CoachingSessionStartRequest;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.SessionCancelRequest;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.SessionFinishRequest;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.SessionProgressResponse;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.UUID;

/**
 * 연습(코칭) 세션 API(C01 준비, C02 촬영 시작)의 HTTP 계층입니다.
 * Controller는 요청 파싱과 응답 포장만 하고, 업무 규칙은 {@link CoachingSessionService}에 맡깁니다.
 * 이 컨트롤러는 Repository를 직접 호출하지 않습니다(Controller → Service → Repository).
 *
 * <p>인증: 두 API 모두 Bearer Access Token만 사용합니다. {@code @AuthenticationPrincipal UUID}는
 * AccessTokenAuthenticationFilter가 토큰을 검증한 뒤 SecurityContext에 넣어 둔 회원 ID입니다.
 */
@RestController
public class CoachingSessionController {
    private final CoachingSessionService service;
    private final SessionFinishService finishes;
    private final SessionLifecycleService lifecycle;

    public CoachingSessionController(CoachingSessionService service, SessionFinishService finishes,
                                     SessionLifecycleService lifecycle) {
        this.service = service;
        this.finishes = finishes;
        this.lifecycle = lifecycle;
    }

    /**
     * C01 연습 세션 준비. 성공 시 201 Created.
     * Idempotency-Key 헤더는 형식 오류를 400으로 직접 처리하려고 문자열로 받습니다
     * (UUID 타입으로 바로 받으면 변환 실패가 다른 예외로 섞여 구분하기 어렵습니다).
     */
    @PostMapping("/api/coaching-sessions")
    public ResponseEntity<ApiResponse<CoachingSessionResponse>> create(@AuthenticationPrincipal UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CoachingSessionCreateRequest body) {
        var response = service.create(userId, parseKey(idempotencyKey), body);
        // 세션 내용(대본 등)이 담긴 응답이므로 중간 캐시에 저장되지 않게 no-store를 지정합니다.
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.created(response));
    }

    /**
     * C02 촬영 시작. 성공 시 200 OK (새 자원이 아니라 기존 세션의 상태 변경이므로 201이 아닙니다).
     * {@code @PathVariable UUID}가 UUID 형식이 아니면 MethodArgumentTypeMismatchException이 발생하며
     * 아래 invalidInput 핸들러가 400 VALIDATION_ERROR로 바꿉니다.
     */
    @PostMapping("/api/coaching-sessions/{sessionId}/start")
    public ResponseEntity<ApiResponse<CoachingSessionResponse>> start(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID sessionId, @Valid @RequestBody CoachingSessionStartRequest body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(service.start(userId, sessionId, body)));
    }

    /**
     * C07 촬영 종료·최종화 접수. 성공 시 202 Accepted(후속 조립·분석은 비동기로 진행됩니다).
     * C01과 같이 Idempotency-Key 헤더가 필요하고, 같은 키·같은 본문의 재시도에는 처음 응답을 돌려줍니다.
     */
    @PostMapping("/api/coaching-sessions/{sessionId}/finish")
    public ResponseEntity<ApiResponse<SessionProgressResponse>> finish(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SessionFinishRequest body) {
        var response = finishes.finish(userId, sessionId, parseKey(idempotencyKey), body);
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(response));
    }

    /**
     * C08 연습 취소. 성공 시 202 Accepted. 멱등 키 없이 반복 호출할 수 있습니다(이미 취소된 세션은 같은 결과).
     * 취소 사유(reason)는 형식만 검증하며 현재는 저장하지 않습니다.
     */
    @PostMapping("/api/coaching-sessions/{sessionId}/cancel")
    public ResponseEntity<ApiResponse<SessionProgressResponse>> cancel(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID sessionId, @Valid @RequestBody SessionCancelRequest body) {
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(lifecycle.cancel(userId, sessionId)));
    }

    /**
     * C09 세션 상태 조회. 성공 시 200 OK. 읽기 전용 GET이라 CSRF 대상이 아닙니다.
     */
    @GetMapping("/api/coaching-sessions/{sessionId}/status")
    public ResponseEntity<ApiResponse<SessionProgressResponse>> status(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID sessionId) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(lifecycle.get(userId, sessionId)));
    }

    /** 헤더가 없거나 UUID 형식이 아니면 입력 오류입니다. 명세에 별도 오류 코드가 없어 VALIDATION_ERROR를 씁니다. */
    private static UUID parseKey(String value) {
        if (value == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    // ===== 지역 예외 처리 =====
    // 이 컨트롤러가 전역 처리기 대신 자체 핸들러를 두는 이유: 요청에 담긴 대본·상황 같은 원문이
    // 프레임워크 기본 오류 출력(예외 메시지)으로 응답에 새어 나가지 않게 하기 위해서입니다(프로젝트 공통 패턴).

    /** 본문 검증 실패, JSON 파싱 실패(잘못된 enum 값 포함), 경로 변수 형식 오류는 모두 400입니다. */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    /** Service가 던진 업무 오류는 ErrorCode가 가진 HTTP 상태 그대로 응답합니다. */
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    /** DB 연결 불가·트랜잭션 시작 실패는 일시적 장애이므로 503입니다. */
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    /** 그 밖의 예외는 원인을 응답에 담지 않고 일반 500으로만 알립니다. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
