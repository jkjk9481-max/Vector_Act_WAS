package com.personalab.vectoract.vector_act_was.domain.upload.presentation;

import com.personalab.vectoract.vector_act_was.domain.coaching.business.AnalysisResultService;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.AnalysisResultResponse;
import com.personalab.vectoract.vector_act_was.domain.upload.business.UploadAnalysisService;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.UploadAnalysisCreateRequest;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.UploadAnalysisResponse;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.UUID;

/**
 * 업로드 영상 분석 API(U06 접수, U07 상태 조회, U08 결과 조회, U09 취소)의 HTTP 계층입니다.
 * 인증: 모두 Bearer Access Token만 사용합니다.
 */
@RestController
public class UploadAnalysisController {
    private final UploadAnalysisService service;
    private final AnalysisResultService results;

    public UploadAnalysisController(UploadAnalysisService service, AnalysisResultService results) {
        this.service = service;
        this.results = results;
    }

    /** U06 업로드 영상 분석 접수. 성공 시 202 Accepted. Idempotency-Key 헤더가 필요합니다. */
    @PostMapping("/api/upload-analyses")
    public ResponseEntity<ApiResponse<UploadAnalysisResponse>> submit(@AuthenticationPrincipal UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody UploadAnalysisCreateRequest body) {
        var response = service.submit(userId, parseKey(idempotencyKey), body);
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(response));
    }

    /** U07 업로드 분석 상태 조회. 성공 시 200 OK. */
    @GetMapping("/api/upload-analyses/{analysisId}")
    public ResponseEntity<ApiResponse<UploadAnalysisResponse>> status(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID analysisId) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(service.status(userId, analysisId)));
    }

    /** U08 업로드 분석 결과 조회. 성공 시 200 OK. C10과 같은 {@link AnalysisResultService}를 씁니다. */
    @GetMapping("/api/upload-analyses/{analysisId}/result")
    public ResponseEntity<ApiResponse<AnalysisResultResponse>> result(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID analysisId) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(results.getForUpload(userId, analysisId)));
    }

    /** U09 업로드 분석 취소. 성공 시 202 Accepted. 멱등 키 없이 반복 호출할 수 있습니다. */
    @PostMapping("/api/upload-analyses/{analysisId}/cancel")
    public ResponseEntity<ApiResponse<UploadAnalysisResponse>> cancel(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID analysisId) {
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(service.cancel(userId, analysisId)));
    }

    private static UUID parseKey(String value) {
        if (value == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    // ===== 지역 예외 처리 =====
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
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
