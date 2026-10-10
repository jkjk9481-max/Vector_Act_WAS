package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.EmailChangeRequestService;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.*;
import com.personalab.vectoract.vector_act_was.global.auth.LoginRateLimiter;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
public class EmailChangeRequestController {
    private final EmailChangeRequestService service;
    private final LoginRateLimiter limiter;

    public EmailChangeRequestController(EmailChangeRequestService service,
            @Value("${auth.email-change-rate-limit.max-attempts:10}") int attempts,
            @Value("${auth.email-change-rate-limit.window-seconds:900}") long windowSeconds) {
        this.service = service;
        this.limiter = new LoginRateLimiter(attempts, windowSeconds);
    }

    @PostMapping("/api/users/me/email-change-requests")
    public ResponseEntity<ApiResponse<EmailChangeRequestResponse>> request(
            @AuthenticationPrincipal UUID userId, @Valid @RequestBody EmailChangeRequest body) {
        limiter.acquire(userId.toString());
        service.request(userId, body.newEmail(), body.currentPassword());
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(new EmailChangeRequestResponse(true)));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    // 영속화 예외에서 토큰·이메일이 노출되지 않도록 예외 원문을 기록하지 않습니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
