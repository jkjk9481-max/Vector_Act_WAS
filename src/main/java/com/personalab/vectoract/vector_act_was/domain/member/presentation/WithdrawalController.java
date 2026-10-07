package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.WithdrawalService;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.WithdrawalResponse;
import com.personalab.vectoract.vector_act_was.global.auth.LoginRateLimiter;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * A10의 HTTP 입구입니다. 헤더·본문·쿠키·상태 코드처럼 통신에 관한 일만 담당합니다.
 * 호출 순서: 인증 필터 → 계정 상태 인터셉터 → Controller → Service → Repository.
 * DB 조회나 엔티티 변경은 Controller에서 하지 않고 Service에 위임합니다.
 */
@RestController
public class WithdrawalController {
    private final WithdrawalService service;
    private final LoginRateLimiter limiter;
    private final boolean secureCookie;

    // 생성자 주입: Spring이 서비스와 설정값을 넣어 줍니다. 콜론 뒤 숫자는 미설정 시 기본값입니다.
    public WithdrawalController(WithdrawalService service,
            @Value("${auth.withdrawal-rate-limit.max-attempts:10}") int maxAttempts,
            @Value("${auth.withdrawal-rate-limit.window-seconds:60}") long windowSeconds,
            @Value("${auth.refresh-cookie.secure:true}") boolean secureCookie) {
        this.service = service;
        // 제한 로직은 재사용하되 별도 객체를 만들어 로그인/A09와 횟수를 공유하지 않습니다.
        this.limiter = new LoginRateLimiter(maxAttempts, windowSeconds);
        this.secureCookie = secureCookie;
    }

    // 이 요청 자체가 최종 탈퇴 확인입니다. 화면에서 취소하면 이 API를 호출하지 않습니다.
    @DeleteMapping("/api/users/me")
    public ResponseEntity<ApiResponse<WithdrawalResponse>> withdraw(
            // 요청 본문의 ID를 믿지 않고, 검증된 Bearer 토큰에서 얻은 본인 UUID만 사용합니다.
            @AuthenticationPrincipal UUID userId,
            // required=false: 헤더가 없어도 Service까지 전달하여 명세의 403 REAUTH_REQUIRED로 처리합니다.
            // true이면 Spring이 Service 호출 전에 일반적인 필수 헤더 누락 오류를 반환하게 됩니다.
            @RequestHeader(name = "X-Reauth-Token", required = false) String token,
            HttpServletRequest request) throws IOException {
        // 회원별 요청 횟수를 세어 재인증 토큰을 무작위로 추측하는 반복 요청을 제한합니다.
        limiter.acquire(userId.toString());
        // 본문 없는 API입니다. 한 바이트만 읽어 길이 헤더가 없는 본문도 거절합니다.
        if (request.getInputStream().read() != -1) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        // Service 호출이 정상 반환될 때는 트랜잭션 커밋까지 끝난 상태입니다.
        // 저장에 실패하면 예외 처리기로 이동하므로 성공 응답이나 쿠키 삭제를 보내지 않습니다.
        var result = service.withdraw(userId, token);
        // 내부 결과를 HTTP 전용 DTO로 옮겨 응답 형식과 업무 로직을 분리합니다.
        var response = new WithdrawalResponse(result.deletedAt(), result.purgeAt());
        // DB 커밋 후 브라우저 쿠키도 지웁니다. 다른 기기의 토큰은 이미 폐기했습니다.
        // 기존 발급 쿠키와 이름/path를 맞추고 Max-Age=0을 보내 브라우저에서 제거합니다.
        // 쿠키 삭제만으로 모든 기기가 로그아웃되지는 않습니다. 서버의 Refresh 폐기가 함께 필요합니다.
        var cookie = ResponseCookie.from("refreshToken", "").httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path("/api/auth").maxAge(Duration.ZERO).build();
        // 202는 Soft Delete가 완료되고 실제 영구 삭제는 나중에 진행된다는 의미입니다.
        return ResponseEntity.accepted().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(response));
    }

    // Service가 선택한 업무 오류를 HTTP 오류 응답으로 바꿉니다. DB 처리를 다시 수행하지 않습니다.
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) {
        return error(exception.getErrorCode());
    }

    // DB 연결/트랜잭션 시작 실패는 일시적 의존 시스템 장애로 구분합니다.
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) {
        return error(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    // 내부 예외 메시지에는 SQL이나 민감한 값이 들어갈 수 있어 정해진 코드와 메시지만 공개합니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) {
        return error(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
