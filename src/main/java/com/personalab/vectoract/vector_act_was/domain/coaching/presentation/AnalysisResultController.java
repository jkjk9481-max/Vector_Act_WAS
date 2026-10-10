package com.personalab.vectoract.vector_act_was.domain.coaching.presentation;

import com.personalab.vectoract.vector_act_was.domain.coaching.business.AnalysisResultService;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.AnalysisResultResponse;
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
 * C10 분석 결과 조회의 HTTP 계층입니다. 읽기 전용 GET이라 CSRF 대상이 아니며 Bearer 인증만 사용합니다.
 * 결과에는 대본·피드백 같은 개인 데이터가 담기므로 캐시를 막습니다.
 */
@RestController
public class AnalysisResultController {
    private final AnalysisResultService service;

    public AnalysisResultController(AnalysisResultService service) {
        this.service = service;
    }

    @GetMapping("/api/coaching-sessions/{sessionId}/result")
    public ResponseEntity<ApiResponse<AnalysisResultResponse>> result(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID sessionId) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(service.get(userId, sessionId)));
    }

    // ===== 지역 예외 처리 =====

    /** 경로의 sessionId가 UUID 형식이 아니면 400입니다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    // 결과 본문이 예외 메시지로 응답에 노출되지 않도록 일반 500으로만 응답합니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
