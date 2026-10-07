package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.WithdrawalService;
import com.personalab.vectoract.vector_act_was.global.auth.LoginRateLimiter;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@RestController
public class WithdrawalController {
    private final WithdrawalService service;
    private final LoginRateLimiter limiter;
    private final boolean secureCookie;

    public WithdrawalController(WithdrawalService service,
            @Value("${auth.withdrawal-rate-limit.max-attempts:10}") int maxAttempts,
            @Value("${auth.withdrawal-rate-limit.window-seconds:60}") long windowSeconds,
            @Value("${auth.refresh-cookie.secure:true}") boolean secureCookie) {
        this.service = service;
        this.limiter = new LoginRateLimiter(maxAttempts, windowSeconds);
        this.secureCookie = secureCookie;
    }

    // 이 요청 자체가 최종 탈퇴 확인입니다. 화면에서 취소하면 이 API를 호출하지 않습니다.
    @DeleteMapping("/api/users/me")
    public ResponseEntity<ApiResponse<WithdrawalService.Result>> withdraw(
            @AuthenticationPrincipal UUID userId,
            @RequestHeader(name = "X-Reauth-Token", required = false) String token,
            HttpServletRequest request) throws IOException {
        limiter.acquire(userId.toString());
        // 본문 없는 API입니다. 한 바이트만 읽어 길이 헤더가 없는 본문도 거절합니다.
        if (request.getInputStream().read() != -1) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        var result = service.withdraw(userId, token);
        // DB 커밋 후 브라우저 쿠키도 지웁니다. 다른 기기의 토큰은 이미 폐기했습니다.
        var cookie = ResponseCookie.from("refreshToken", "").httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path("/api/auth").maxAge(Duration.ZERO).build();
        return ResponseEntity.accepted().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(result));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) {
        return error(exception.getErrorCode()); }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) {
        return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) {
        return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
