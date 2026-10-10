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

/**
 * S01 이미지 OCR 접수(POST), S02 OCR 상태·결과 조회(GET)의 HTTP 계층입니다.
 * 접수는 처리가 끝나기를 기다리지 않고 202 Accepted로 응답하며, 클라이언트는 S02를 폴링해 결과를 얻습니다.
 */
@RestController
public class ScriptExtractionController {
    // S01 이미지는 1B~10MiB입니다(API 명세서). 바이트 단위로 계산합니다.
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final ScriptJobService service;
    // 회원별로 접수 횟수를 제한합니다. 기준값은 명세에 없어 설정(script.ocr.rate-limit.*)으로 둡니다.
    private final LoginRateLimiter limiter;

    public ScriptExtractionController(ScriptJobService service,
            @Value("${script.ocr.rate-limit.max-attempts:10}") int attempts,
            @Value("${script.ocr.rate-limit.window-seconds:60}") long windowSeconds) {
        this.service = service;
        this.limiter = new LoginRateLimiter(attempts, windowSeconds);
    }

    /** S01: multipart의 {@code file} 파트로 이미지를 받아 OCR 작업을 접수합니다. */
    @PostMapping(value = "/api/script-extractions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ScriptExtractionResponse>> submit(@AuthenticationPrincipal UUID userId,
            @RequestPart("file") MultipartFile file) throws IOException {
        limiter.acquire(userId.toString());
        // 1B~10MiB. 내용을 메모리로 읽기 전에 크기부터 확인합니다.
        if (file.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (file.getSize() > MAX_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        return respond(HttpStatus.ACCEPTED, service.submit(userId, file.getBytes()));
    }

    /** S02: 작업 상태와 결과를 조회합니다. 타인의 작업이거나 없는 작업이면 404, 만료되면 410입니다. */
    @GetMapping("/api/script-extractions/{extractionId}")
    public ResponseEntity<ApiResponse<ScriptExtractionResponse>> get(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID extractionId) {
        return respond(HttpStatus.OK, service.get(userId, extractionId));
    }

    // Service의 값 객체를 응답 DTO로 바꿉니다. 대본 본문이 담길 수 있어 캐시를 막습니다(no-store).
    private ResponseEntity<ApiResponse<ScriptExtractionResponse>> respond(HttpStatus status, ScriptJobService.View view) {
        var body = new ScriptExtractionResponse(view.jobId(), view.status(), view.content(),
                view.failureCode(), view.expiresAt());
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(body));
    }

    // multipart 파트 누락·경로 변수(UUID) 형식 오류는 400, 컨테이너 한도 초과(MaxUploadSizeExceeded)는 413입니다.
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
