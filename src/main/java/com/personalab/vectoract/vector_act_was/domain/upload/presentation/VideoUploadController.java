package com.personalab.vectoract.vector_act_was.domain.upload.presentation;

import com.personalab.vectoract.vector_act_was.domain.upload.business.VideoUploadService;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.PartUploadUrlRequest;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.PartUploadUrlResponse;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.UploadCompleteRequest;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.VideoUploadCreateRequest;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.VideoUploadResponse;
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
 * 기존 영상 대용량 업로드 API(U01~U05)의 HTTP 계층입니다. CoachingSessionController와 같은 패턴입니다.
 * 인증: 모두 Bearer Access Token만 사용합니다.
 */
@RestController
public class VideoUploadController {
    private final VideoUploadService service;

    public VideoUploadController(VideoUploadService service) {
        this.service = service;
    }

    /** U01 대용량 업로드 시작. 성공 시 201 Created. Idempotency-Key 헤더가 필요합니다(C01과 같은 방식). */
    @PostMapping("/api/video-uploads")
    public ResponseEntity<ApiResponse<VideoUploadResponse>> create(@AuthenticationPrincipal UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VideoUploadCreateRequest body) {
        var response = service.create(userId, parseKey(idempotencyKey), body);
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.created(response));
    }

    /** U02 Part 업로드 URL 발급. 성공 시 200 OK. */
    @PostMapping("/api/video-uploads/{uploadId}/parts/{partNumber}/upload-url")
    public ResponseEntity<ApiResponse<PartUploadUrlResponse>> partUploadUrl(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID uploadId, @PathVariable int partNumber, @Valid @RequestBody PartUploadUrlRequest body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(service.partUploadUrl(userId, uploadId, partNumber, body)));
    }

    /** U03 업로드 완료·검증 접수. 성공 시 202 Accepted(검증은 동기로 처리하지만, 조립 성격상 명세의 상태 코드를 따릅니다). */
    @PostMapping("/api/video-uploads/{uploadId}/complete")
    public ResponseEntity<ApiResponse<VideoUploadResponse>> complete(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID uploadId, @Valid @RequestBody UploadCompleteRequest body) {
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(service.complete(userId, uploadId, body)));
    }

    /** U04 업로드 상태 조회. 성공 시 200 OK. 읽기 전용 GET이라 CSRF 대상이 아닙니다. */
    @GetMapping("/api/video-uploads/{uploadId}")
    public ResponseEntity<ApiResponse<VideoUploadResponse>> status(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID uploadId) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(service.status(userId, uploadId)));
    }

    /** U05 업로드 취소. 성공 시 202 Accepted. */
    @DeleteMapping("/api/video-uploads/{uploadId}")
    public ResponseEntity<ApiResponse<VideoUploadResponse>> cancel(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID uploadId) {
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(service.cancel(userId, uploadId)));
    }

    /** 헤더가 없거나 UUID 형식이 아니면 입력 오류입니다(CoachingSessionController와 동일). */
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
