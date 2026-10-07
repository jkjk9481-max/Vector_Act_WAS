package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.PasswordResetRequestService;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.*;
import com.personalab.vectoract.vector_act_was.global.auth.*;
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

/**
 * A11의 HTTP 입구입니다. 입력 검증·요청 제한·응답 변환만 담당하고 Repository를 직접 호출하지 않습니다.
 * 순서: CSRF 필터 → Controller → PasswordResetRequestService → Repository.
 * 회원이 존재하는지는 Service 내부 정보이며 응답에는 포함하지 않습니다.
 */
@RestController
public class PasswordResetRequestController {
    private final PasswordResetRequestService service;
    private final RefreshTokenGenerator generator;
    private final LoginRateLimiter ipLimiter;
    private final LoginRateLimiter emailLimiter;

    public PasswordResetRequestController(PasswordResetRequestService service, RefreshTokenGenerator generator,
            @Value("${auth.password-reset-rate-limit.ip-max-attempts:10}") int ipAttempts,
            @Value("${auth.password-reset-rate-limit.email-max-attempts:3}") int emailAttempts,
            @Value("${auth.password-reset-rate-limit.window-seconds:900}") long windowSeconds) {
        this.service = service;
        this.generator = generator;
        // 기존 제한 알고리즘만 재사용하고 로그인/A09/A10 카운터와 분리합니다.
        // IP 제한은 대량 조회, 이메일 제한은 여러 IP에서 한 주소로 메일을 반복 요청하는 행위를 줄입니다.
        this.ipLimiter = new LoginRateLimiter(ipAttempts, windowSeconds);
        this.emailLimiter = new LoginRateLimiter(emailAttempts, windowSeconds);
    }

    @PostMapping("/api/auth/password-reset-requests")
    public ResponseEntity<ApiResponse<PasswordResetRequestResponse>> request(
            @Valid @RequestBody PasswordResetRequest body, HttpServletRequest request) {
        // 서버가 확인한 주소를 사용합니다. 클라이언트가 임의로 보낸 X-Forwarded-For를 믿지 않습니다.
        ipLimiter.acquire(request.getRemoteAddr());
        // DTO가 정규화한 이메일의 해시로 집계합니다. 가입 여부를 확인하기 전에 모든 주소에 적용합니다.
        // 그러므로 429 응답만으로 가입 여부를 알 수 없고, 대소문자로 제한을 우회할 수도 없습니다.
        emailLimiter.acquire(generator.hash(body.email()));
        service.request(body.email());
        // Service는 가입 여부나 원문 토큰을 반환하지 않습니다. 항상 같은 DTO/상태/헤더를 반환합니다.
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(new PasswordResetRequestResponse(true)));
    }

    // 이메일 누락/빈 문자열/잘못된 형식/잘못된 JSON은 개인정보를 포함하지 않는 공통 400입니다.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    // DB 자체가 불가능한 경우를 503으로 구분합니다. '없는 회원'은 여기에 해당하지 않습니다.
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
