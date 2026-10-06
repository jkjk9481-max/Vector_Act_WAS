package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.ReauthService;
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
 * A09 요청 흐름: Bearer 인증 필터 → DTO 검증 → 요청 횟수 검사 → Service → Repository.
 * Controller는 HTTP만 담당하고, 저장 트랜잭션은 Service가 담당합니다.
 * @RestController는 반환 객체를 JSON 응답으로 변환하도록 Spring에 알려줍니다.
 */
@RestController
public class ReauthController {
    private final ReauthService service;
    private final LoginRateLimiter limiter;

    // Spring이 Service와 설정값을 주입합니다. 콜론 뒤 값은 설정이 없을 때 사용하는 기본값입니다.
    public ReauthController(ReauthService service,
            @Value("${auth.reauth-rate-limit.max-attempts:10}") int maxAttempts,
            @Value("${auth.reauth-rate-limit.window-seconds:60}") long windowSeconds) {
        this.service = service;
        // 로그인 제한 로직만 재사용하며 카운터는 별도로 둡니다. 회원별 60초 10회가 기본입니다.
        this.limiter = new LoginRateLimiter(maxAttempts, windowSeconds);
    }

    // @AuthenticationPrincipal은 검증된 토큰의 회원 ID를 받습니다. 요청 본문의 ID를 믿지 않습니다.
    // @RequestBody는 JSON을 DTO로 변환하고 @Valid는 필수 입력을 검사합니다.
    @PostMapping("/api/auth/reauth")
    public ResponseEntity<ApiResponse<ReauthResponse>> reauth(
            @AuthenticationPrincipal UUID userId, @Valid @RequestBody ReauthRequest request) {
        // 현재 비밀번호 추측 공격을 제한합니다. 형식 검증을 통과한 요청을 회원 ID별로 집계합니다.
        limiter.acquire(userId.toString());
        var result = service.issue(userId, request.password());
        // 서비스 트랜잭션의 커밋 후에만 원문 토큰을 반환합니다. DB 오류라면 이 줄에 도달하지 않습니다.
        // no-store/no-cache는 민감한 토큰 응답을 브라우저나 중간 캐시에 보관하지 않도록 합니다.
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(new ReauthResponse(result.reauthToken(), result.expiresAt())));
    }

    // 이 Controller의 예외 처리기가 전역 처리기보다 우선합니다. 다른 API 오류 코드는 바꾸지 않습니다.
    // 필수값 누락/JSON 오류는 400이며 거절한 비밀번호 원문을 응답하지 않습니다.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) {
        return error(ErrorCode.VALIDATION_ERROR);
    }

    // 현재 비밀번호 오류(403), 회원 없음(404), 요청 제한(429)을 Service/제한기가 선택한 코드로 반환합니다.
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) {
        return error(exception.getErrorCode());
    }

    // DB 연결 자원 문제나 트랜잭션 시작 실패는 공통 503입니다.
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> dependencyUnavailable(Exception ignored) {
        return error(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    // 예상하지 못한 저장/처리 실패는 공통 500으로 응답하며 내부 예외 내용을 노출하지 않습니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internalError(Exception ignored) {
        return error(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
