package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.LoginService;
import com.personalab.vectoract.vector_act_was.domain.member.business.RefreshService;
import com.personalab.vectoract.vector_act_was.domain.member.business.LogoutService;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class LoginController {
    private final LoginService loginService;
    private final RefreshService refreshService;
    private final LogoutService logoutService;
    private final boolean secureCookie;

    public LoginController(LoginService loginService, RefreshService refreshService, LogoutService logoutService,
                           @Value("${auth.refresh-cookie.secure:true}") boolean secureCookie) {
        this.loginService = loginService;
        this.refreshService = refreshService;
        this.logoutService = logoutService;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        var result = loginService.login(request.email(), request.password());
        return tokenResponse(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken) {
        return tokenResponse(refreshService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<LogoutResponse>> logout(
            @CookieValue(name = "refreshToken", required = false) String refreshToken) {
        logoutService.logout(refreshToken);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(new LogoutResponse(true)));
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        // HttpOnly는 JavaScript에서 쿠키를 읽지 못하게 합니다. Secure는 HTTPS에서만 전송하게 합니다.
        return ResponseCookie.from("refreshToken", value).httpOnly(true)
                .secure(secureCookie).sameSite("Lax").path("/api/auth").maxAge(maxAge).build();
    }

    private ResponseEntity<ApiResponse<LoginResponse>> tokenResponse(LoginService.Result result) {
        var cookie = refreshCookie(result.refreshToken(), Duration.ofDays(14));
        var user = new SignupResponse(result.userId(), result.name(), result.email(), result.createdAt());
        var response = new LoginResponse(result.accessToken(), "Bearer", AccessTokenProvider.EXPIRES_IN, user);
        // 토큰 응답은 브라우저/중간 캐시에 보관하지 않도록 하고 Refresh Token은 헤더에만 담습니다.
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(response));
    }

    // 로그인 명세의 오류는 401/429입니다. 입력 누락/형식 오류도 로그인에서는 401로 통일합니다.
    // Controller 전용 처리이므로 기존 회원가입의 400 VALIDATION_ERROR에는 영향을 주지 않습니다.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidRequest(Exception ignored) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(ErrorCode.INVALID_CREDENTIALS));
    }
}
