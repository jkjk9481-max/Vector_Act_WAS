package com.personalab.vectoract.vector_act_was.domain.coaching.presentation;

import com.personalab.vectoract.vector_act_was.domain.coaching.business.ConnectionTicketService;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.ConnectionTicketResponse;
import com.personalab.vectoract.vector_act_was.global.auth.LoginRateLimiter;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.UUID;

/**
 * C03 연결 티켓 발급의 HTTP 계층입니다. 본문 없이 Bearer만으로 호출합니다.
 * 티켓은 인증 수단이므로 응답을 캐시하지 않고, 예외 원문이 응답에 새지 않도록 지역 예외 처리를 둡니다.
 */
@RestController
public class ConnectionTicketController {
    private final ConnectionTicketService service;
    // 티켓 남발을 막기 위해 회원별로 발급 횟수를 제한합니다. 기준값은 명세에 없어 설정으로 둡니다.
    private final LoginRateLimiter limiter;

    public ConnectionTicketController(ConnectionTicketService service,
            @Value("${coaching.connection-ticket.rate-limit.max-attempts:20}") int attempts,
            @Value("${coaching.connection-ticket.rate-limit.window-seconds:60}") long windowSeconds) {
        this.service = service;
        this.limiter = new LoginRateLimiter(attempts, windowSeconds);
    }

    /** 성공하면 201 Created와 함께 티켓·만료 시각·접속 주소를 돌려줍니다. */
    @PostMapping("/api/coaching-sessions/{sessionId}/connection-tickets")
    public ResponseEntity<ApiResponse<ConnectionTicketResponse>> issue(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID sessionId) {
        limiter.acquire(userId.toString());
        var issued = service.issue(userId, sessionId);
        var body = new ConnectionTicketResponse(issued.ticket(), issued.expiresAt(), issued.webSocketUrl());
        // 티켓이 담긴 응답이 브라우저·중간 캐시에 저장되지 않도록 합니다.
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache").body(ApiResponse.created(body));
    }

    // ===== 지역 예외 처리 =====

    /** 경로의 sessionId가 UUID 형식이 아니면 400입니다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    // 서명 오류 등 예상하지 못한 예외의 원문이 응답에 노출되지 않도록 일반 500으로만 응답합니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
