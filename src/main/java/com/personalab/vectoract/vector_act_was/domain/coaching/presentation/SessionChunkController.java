package com.personalab.vectoract.vector_act_was.domain.coaching.presentation;

import com.personalab.vectoract.vector_act_was.domain.coaching.business.ChunkUploadService;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.ChunkUploadUrlRequest;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.ChunkUploadUrlResponse;
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
 * 실시간 촬영 청크 업로드 API(C04 URL 발급, 이후 C05 검증·C06 현황)의 HTTP 계층입니다.
 * Bearer 인증만 사용하며, 서명된 업로드 URL이 응답에 담기므로 캐시를 막고 예외 원문은 응답에 싣지 않습니다.
 */
@RestController
public class SessionChunkController {
    private final ChunkUploadService uploads;

    public SessionChunkController(ChunkUploadService uploads) {
        this.uploads = uploads;
    }

    /**
     * C04: 청크 업로드 URL을 발급합니다. 성공 시 200 OK.
     * 청크 번호 범위(0~120) 검사는 Service에서 하므로, 경로의 정수 형식 오류만 아래 핸들러가 400으로 바꿉니다.
     */
    @PostMapping("/api/coaching-sessions/{sessionId}/chunks/{chunkIndex}/upload-url")
    public ResponseEntity<ApiResponse<ChunkUploadUrlResponse>> issueUploadUrl(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID sessionId, @PathVariable int chunkIndex,
            @Valid @RequestBody ChunkUploadUrlRequest body) {
        var response = uploads.issue(userId, sessionId, chunkIndex, body);
        // 서명된 URL은 그 자체가 업로드 권한이라 브라우저·중간 캐시에 남지 않게 합니다.
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache").body(ApiResponse.ok(response));
    }

    // ===== 지역 예외 처리 =====

    /** 본문 검증 실패, JSON 파싱 실패, 경로 변수(UUID·정수) 형식 오류는 400입니다. */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    // 서명 오류 등 예상하지 못한 예외의 원문이 응답에 노출되지 않도록 일반 500으로만 응답합니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
