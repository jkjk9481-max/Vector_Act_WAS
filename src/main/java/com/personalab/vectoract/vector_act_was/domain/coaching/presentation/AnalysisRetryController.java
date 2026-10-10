package com.personalab.vectoract.vector_act_was.domain.coaching.presentation;

import com.personalab.vectoract.vector_act_was.domain.coaching.business.AnalysisRetryService;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.AnalysisRetryResponse;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.UUID;

/**
 * C11 실패한 분석 재접수의 HTTP 계층입니다. 본문 없이 Bearer와 Idempotency-Key로 호출합니다.
 */
@RestController
public class AnalysisRetryController {
    private final AnalysisRetryService service;

    public AnalysisRetryController(AnalysisRetryService service) {
        this.service = service;
    }

    /** 재분석이 접수되면 202 Accepted와 새 시도 번호를 돌려줍니다. */
    @PostMapping("/api/coaching-sessions/{sessionId}/analysis-retries")
    public ResponseEntity<ApiResponse<AnalysisRetryResponse>> retry(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var response = service.retry(userId, sessionId, parseKey(idempotencyKey));
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(response));
    }

    // 헤더가 없거나 UUID 형식이 아니면 입력 오류입니다. 명세에 별도 오류 코드가 없어 VALIDATION_ERROR를 씁니다.
    private static UUID parseKey(String value) {
        if (value == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    // ===== 지역 예외 처리 =====

    /** 경로의 sessionId가 UUID 형식이 아니면 400입니다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
