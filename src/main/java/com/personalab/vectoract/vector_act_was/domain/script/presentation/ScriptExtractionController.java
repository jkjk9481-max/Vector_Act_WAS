package com.personalab.vectoract.vector_act_was.domain.script.presentation;

import com.personalab.vectoract.vector_act_was.domain.script.business.ScriptJobService;
import com.personalab.vectoract.vector_act_was.domain.script.presentation.dto.ScriptExtractionResponse;
import com.personalab.vectoract.vector_act_was.global.auth.LoginRateLimiter;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import java.io.IOException;
import java.util.UUID;

@RestController
public class ScriptExtractionController {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final ScriptJobService service;
    private final LoginRateLimiter limiter;

    public ScriptExtractionController(ScriptJobService service,
            @Value("${script.ocr.rate-limit.max-attempts:10}") int attempts,
            @Value("${script.ocr.rate-limit.window-seconds:60}") long windowSeconds) {
        this.service = service;
        this.limiter = new LoginRateLimiter(attempts, windowSeconds);
    }

    @PostMapping(value = "/api/script-extractions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ScriptExtractionResponse>> submit(@AuthenticationPrincipal UUID userId,
            @RequestPart("file") MultipartFile file) throws IOException {
        limiter.acquire(userId.toString());
        // 1B~10MiB. 내용을 메모리로 읽기 전에 크기부터 확인합니다.
        if (file.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (file.getSize() > MAX_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        return respond(HttpStatus.ACCEPTED, service.submit(userId, file.getBytes()));
    }

    @GetMapping("/api/script-extractions/{extractionId}")
    public ResponseEntity<ApiResponse<ScriptExtractionResponse>> get(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID extractionId) {
        return respond(HttpStatus.OK, service.get(userId, extractionId));
    }

    private ResponseEntity<ApiResponse<ScriptExtractionResponse>> respond(HttpStatus status, ScriptJobService.View view) {
        var body = new ScriptExtractionResponse(view.jobId(), view.status(), view.content(),
                view.failureCode(), view.expiresAt());
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(body));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class,
            MultipartException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception exception) {
        return error(exception instanceof MaxUploadSizeExceededException
                ? ErrorCode.FILE_TOO_LARGE : ErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    // 저장소·DB 예외 원문이 응답에 노출되지 않도록 지역 처리합니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
