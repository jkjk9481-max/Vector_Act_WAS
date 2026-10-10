package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.PasswordResetService;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.*;
import com.personalab.vectoract.vector_act_was.global.auth.LoginRateLimiter;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestController
public class PasswordResetController {
    private final PasswordResetService service;
    private final LoginRateLimiter limiter;

    public PasswordResetController(PasswordResetService service,
            @Value("${auth.password-reset-completion-rate-limit.max-attempts:10}") int attempts,
            @Value("${auth.password-reset-completion-rate-limit.window-seconds:60}") long windowSeconds) {
        this.service = service;
        this.limiter = new LoginRateLimiter(attempts, windowSeconds);
    }

    @PostMapping("/api/auth/password-resets")
    public ResponseEntity<ApiResponse<PasswordResetResponse>> reset(
            @Valid @RequestBody PasswordResetRequestBody body, HttpServletRequest request) {
        limiter.acquire(request.getRemoteAddr());
        service.reset(body.resetToken(), body.newPassword(), body.newPasswordConfirm());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(new PasswordResetResponse(true)));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    // DB 예외나 검증 오류에서 비밀번호·토큰 원문이 노출되지 않도록 지역 처리합니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
