package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.ChangePasswordService;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.*;
import com.personalab.vectoract.vector_act_was.global.auth.LoginRateLimiter;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.time.Duration;
import java.util.UUID;

@RestController
public class ChangePasswordController {
    private final ChangePasswordService service;
    private final LoginRateLimiter limiter;
    private final boolean secureCookie;

    public ChangePasswordController(ChangePasswordService service,
            @Value("${auth.password-change-rate-limit.max-attempts:10}") int maxAttempts,
            @Value("${auth.password-change-rate-limit.window-seconds:60}") long windowSeconds,
            @Value("${auth.refresh-cookie.secure:true}") boolean secureCookie) {
        this.service = service;
        this.limiter = new LoginRateLimiter(maxAttempts, windowSeconds);
        this.secureCookie = secureCookie;
    }

    @PatchMapping("/api/users/me/password")
    public ResponseEntity<ApiResponse<ChangePasswordResponse>> changePassword(
            @AuthenticationPrincipal UUID userId, @Valid @RequestBody ChangePasswordRequest request) {
        limiter.acquire(userId.toString());
        service.changePassword(userId, request.currentPassword(), request.newPassword(), request.newPasswordConfirm());
        var cookie = ResponseCookie.from("refreshToken", "").httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path("/api/auth").maxAge(Duration.ZERO).build();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.ok(new ChangePasswordResponse(true)));
    }

    // A08 명세의 오류 코드만 적용하며, 예외 메시지나 비밀번호 원문을 응답하지 않습니다.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) {
        return error(ErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) {
        return error(exception.getErrorCode());
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> dependencyUnavailable(Exception ignored) {
        return error(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internalError(Exception ignored) {
        return error(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
