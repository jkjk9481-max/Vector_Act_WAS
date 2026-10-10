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

/**
 * A14 이메일 변경 완료의 HTTP 계층입니다. 메일 링크로 받은 토큰을 제출하면 이메일이 확정됩니다.
 * 업무 규칙은 {@link EmailChangeCompletionService}에 있고, 여기서는 요청 제한과 응답 포장만 합니다.
 */
@RestController
public class EmailChangeCompletionController {
    private final EmailChangeCompletionService service;
    // 비로그인 공개 API라 회원 ID가 없습니다. 토큰 무작위 대입을 막기 위해 IP 기준으로 횟수를 제한합니다.
    private final LoginRateLimiter limiter;

    public EmailChangeCompletionController(EmailChangeCompletionService service,
            @Value("${auth.email-change-completion-rate-limit.max-attempts:10}") int attempts,
            @Value("${auth.email-change-completion-rate-limit.window-seconds:60}") long windowSeconds) {
        this.service = service;
        this.limiter = new LoginRateLimiter(attempts, windowSeconds);
    }

    // 인증 링크는 비로그인 브라우저에서도 열 수 있으므로 Bearer 없이 CSRF만 요구합니다.
    // (그래서 SignupSecurityConfig의 publicRequests에는 있고, CSRF 제외 목록에는 없습니다.)
    @PostMapping("/api/users/me/email-changes")
    public ResponseEntity<ApiResponse<EmailChangeCompletionResponse>> complete(
            @Valid @RequestBody EmailChangeCompletionRequest body, HttpServletRequest request) {
        // 제한을 초과하면 RATE_LIMITED(429) 예외가 발생해 아래 처리로 가지 않습니다.
        limiter.acquire(request.getRemoteAddr());
        service.complete(body.changeToken());
        // 토큰이 오가는 응답이므로 브라우저·중간 캐시가 저장하지 않게 합니다.
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(new EmailChangeCompletionResponse(true)));
    }

    // ===== 지역 예외 처리 (토큰 계열 컨트롤러의 공통 패턴) =====

    /** 본문 검증 실패와 JSON 파싱 실패는 400입니다. */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    /** Service가 던진 업무 오류는 ErrorCode의 HTTP 상태로 응답합니다. */
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    /** DB에 연결할 수 없는 일시적 장애는 503입니다. */
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    // 토큰·이메일 원문이 예외 메시지로 노출되지 않도록 지역 처리합니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
