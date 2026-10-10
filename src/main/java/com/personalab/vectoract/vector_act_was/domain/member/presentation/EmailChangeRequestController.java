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

/**
 * A13 이메일 변경 요청의 HTTP 계층입니다. Bearer로 인증된 회원이 현재 비밀번호와 새 이메일을 보내면
 * 확인 메일 발송이 접수됩니다(202 Accepted). 업무 규칙은 {@link EmailChangeRequestService}에 있습니다.
 */
@RestController
public class EmailChangeRequestController {
    private final EmailChangeRequestService service;
    // 비밀번호 대입 시도를 막기 위해 회원 ID 기준으로 횟수를 제한합니다(단일 서버 메모리 방식).
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
        // 메일 발송은 커밋 이후 별도 단계라서 "처리 완료"가 아니라 "접수"를 뜻하는 202를 사용합니다.
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(new EmailChangeRequestResponse(true)));
    }

    // ===== 지역 예외 처리 (비밀번호·이메일 원문이 응답에 새지 않도록 컨트롤러 안에서 처리) =====

    /** 본문 검증 실패와 JSON 파싱 실패는 400입니다. */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    /** Service가 던진 업무 오류(비밀번호 불일치, 이메일 중복 등)는 ErrorCode의 HTTP 상태로 응답합니다. */
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    /** DB에 연결할 수 없는 일시적 장애는 503입니다. */
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    // 영속화 예외에서 토큰·이메일이 노출되지 않도록 예외 원문을 기록하지 않습니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
