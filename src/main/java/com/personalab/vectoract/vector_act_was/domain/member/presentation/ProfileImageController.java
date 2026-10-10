package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.MeService;
import com.personalab.vectoract.vector_act_was.domain.member.business.ProfileImageService;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.MeResponse;
import com.personalab.vectoract.vector_act_was.global.auth.LoginRateLimiter;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.UUID;

@RestController
public class ProfileImageController {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private final ProfileImageService service;
    private final LoginRateLimiter limiter;

    public ProfileImageController(ProfileImageService service,
            @Value("${auth.profile-image-rate-limit.max-attempts:10}") int attempts,
            @Value("${auth.profile-image-rate-limit.window-seconds:60}") long windowSeconds) {
        this.service = service;
        this.limiter = new LoginRateLimiter(attempts, windowSeconds);
    }

    @PutMapping(value = "/api/users/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MeResponse>> upload(@AuthenticationPrincipal UUID userId,
            @RequestPart("file") MultipartFile file) throws IOException {
        limiter.acquire(userId.toString());
        // 1B~5MiB. 내용을 메모리로 읽기 전에 크기부터 확인합니다.
        if (file.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (file.getSize() > MAX_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        return ok(service.replace(userId, file.getBytes()));
    }

    @DeleteMapping("/api/users/me/profile-image")
    public ResponseEntity<ApiResponse<MeResponse>> delete(@AuthenticationPrincipal UUID userId) {
        return ok(service.remove(userId));
    }

    private ResponseEntity<ApiResponse<MeResponse>> ok(MeService.Result result) {
        var response = new MeResponse(result.userId(), result.name(), result.email(),
                result.createdAt(), result.profileImageUrl());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(response));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception exception) {
        return error(exception instanceof MaxUploadSizeExceededException
                ? ErrorCode.FILE_TOO_LARGE : ErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    // 저장소·이미지 처리 예외 원문이 응답에 노출되지 않도록 지역 처리합니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
