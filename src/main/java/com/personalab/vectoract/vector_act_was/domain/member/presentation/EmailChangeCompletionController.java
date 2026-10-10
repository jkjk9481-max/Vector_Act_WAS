package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.EmailChangeCompletionService;
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
public class EmailChangeCompletionController {
    private final EmailChangeCompletionService service;
    private final LoginRateLimiter limiter;

    public EmailChangeCompletionController(EmailChangeCompletionService service,
            @Value("${auth.email-change-completion-rate-limit.max-attempts:10}") int attempts,
            @Value("${auth.email-change-completion-rate-limit.window-seconds:60}") long windowSeconds) {
        this.service = service;
        this.limiter = new LoginRateLimiter(attempts, windowSeconds);
    }

    // 인증 링크는 비로그인 브라우저에서도 열 수 있으므로 Bearer 없이 CSRF만 요구합니다.
    @PostMapping("/api/users/me/email-changes")
    public ResponseEntity<ApiResponse<EmailChangeCompletionResponse>> complete(
            @Valid @RequestBody EmailChangeCompletionRequest body, HttpServletRequest request) {
        limiter.acquire(request.getRemoteAddr());
        service.complete(body.changeToken());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(new EmailChangeCompletionResponse(true)));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    // 토큰·이메일 원문이 예외 메시지로 노출되지 않도록 지역 처리합니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
