package com.personalab.vectoract.vector_act_was.domain.coaching.presentation;

import com.personalab.vectoract.vector_act_was.domain.coaching.business.CoachingSessionService;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.CoachingSessionCreateRequest;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.CoachingSessionResponse;
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
import java.util.UUID;

@RestController
public class CoachingSessionController {
    private final CoachingSessionService service;

    public CoachingSessionController(CoachingSessionService service) {
        this.service = service;
    }

    @PostMapping("/api/coaching-sessions")
    public ResponseEntity<ApiResponse<CoachingSessionResponse>> create(@AuthenticationPrincipal UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CoachingSessionCreateRequest body) {
        var response = service.create(userId, parseKey(idempotencyKey), body);
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.created(response));
    }

    // 헤더가 없거나 UUID 형식이 아니면 입력 오류입니다.
    private static UUID parseKey(String value) {
        if (value == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    // 대본·상황 원문이 예외 메시지로 응답에 노출되지 않도록 원문을 돌려주지 않습니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
