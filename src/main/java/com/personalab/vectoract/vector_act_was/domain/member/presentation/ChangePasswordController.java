package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.ChangePasswordService;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.*;
import com.personalab.vectoract.vector_act_was.global.auth.LoginRateLimiter;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.time.Duration;
import java.util.UUID;

/**
 * A08의 HTTP 요청과 응답을 담당합니다. 비밀번호 비교와 DB 변경은 Service에 맡깁니다.
 * 전체 흐름: 인증 필터 → 요청 DTO 변환·검증 → 이 Controller → Service → Repository → DB.
 * Service의 트랜잭션이 커밋된 뒤 이곳으로 돌아오면 쿠키 삭제와 성공 응답을 만듭니다.
 * 인증 필터에서 발생하는 401은 Controller에 도착하기 전에 필터가 직접 응답합니다.
 */
// 반환 객체를 JSON 응답으로 변환하도록 Spring에 알려주는 어노테이션입니다.
@RestController
public class ChangePasswordController {
    // final은 생성자에서 받은 참조를 다른 객체로 바꾸지 못하게 합니다.
    private final ChangePasswordService service;
    private final LoginRateLimiter limiter;
    private final boolean secureCookie;

    // 생성자 주입: Spring이 Service와 설정값을 전달합니다.
    // @Value의 ${설정명:기본값}은 설정이 없을 때 콜론 뒤 값을 사용한다는 뜻입니다.
    public ChangePasswordController(ChangePasswordService service,
            @Value("${auth.password-change-rate-limit.max-attempts:10}") int maxAttempts,
            @Value("${auth.password-change-rate-limit.window-seconds:60}") long windowSeconds,
            @Value("${auth.refresh-cookie.secure:true}") boolean secureCookie) {
        this.service = service;
        // 기존 제한 로직을 재사용하되 별도 객체로 만들어 로그인 시도 횟수와 분리합니다.
        // 기본값은 회원별 60초 동안 10회이며, 횟수는 이 서버의 메모리에 보관됩니다.
        this.limiter = new LoginRateLimiter(maxAttempts, windowSeconds);
        this.secureCookie = secureCookie;
    }

    // PATCH 요청을 이 메서드에 연결합니다. Controller에는 @Transactional이 필요하지 않습니다.
    // @AuthenticationPrincipal: 검증된 토큰에서 인증 필터가 추출한 회원 UUID를 받습니다.
    // @RequestBody: JSON을 ChangePasswordRequest로 변환합니다.
    // @Valid: DTO의 필수값·길이·바이트 검사를 실행하며, 실패하면 메서드 본문에 진입하지 않습니다.
    @PatchMapping("/api/users/me/password")
    public ResponseEntity<ApiResponse<ChangePasswordResponse>> changePassword(
            @AuthenticationPrincipal UUID userId, @Valid @RequestBody ChangePasswordRequest request) {
        // 1. 현재 비밀번호를 반복해서 추측하지 못하도록 회원 ID별 요청 횟수를 검사합니다.
        // 한도를 넘으면 예외가 발생하므로 아래 비밀번호 비교나 DB 변경은 실행되지 않습니다.
        limiter.acquire(userId.toString());
        // 2. record의 currentPassword() 같은 메서드는 해당 필드 값을 꺼내는 접근자입니다.
        // 대상 ID는 요청 본문에서 받지 않으므로 다른 회원 ID를 보내도 그 회원을 수정할 수 없습니다.
        service.changePassword(userId, request.currentPassword(), request.newPassword(), request.newPasswordConfirm());
        // 3. Service 호출이 정상 반환됐다는 것은 DB 커밋까지 성공했다는 뜻입니다.
        // DB의 모든 기기 토큰은 이미 폐기됐고, 여기서는 현재 브라우저의 쿠키도 삭제합니다.
        // 같은 이름과 Path로 빈 값·Max-Age=0을 보내야 기존 쿠키가 삭제됩니다.
        // HttpOnly는 JS 접근 금지, Secure는 HTTPS 전용, SameSite=Lax는 사이트 간 전송 제한입니다.
        var cookie = ResponseCookie.from("refreshToken", "").httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path("/api/auth").maxAge(Duration.ZERO).build();
        // 4. ok()는 HTTP 200, no-store는 응답을 캐시에 보관하지 말라는 지시입니다.
        // Set-Cookie는 브라우저에 쿠키 삭제를 전달하고, body는 공통 JSON 형식으로 감쌉니다.
        // 결과는 data.accepted=true이며 새 Access/Refresh Token을 발급하지 않습니다.
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.ok(new ChangePasswordResponse(true)));
    }

    // A08 명세의 오류 코드만 적용하며, 예외 메시지나 비밀번호 원문을 응답하지 않습니다.
    // @ExceptionHandler는 이 Controller의 요청 처리 중 발생한 지정 예외를 응답으로 바꿉니다.
    // 아래 지역 처리기는 전역 처리기보다 우선하므로 다른 API의 오류 형식을 바꾸지 않습니다.
    // DTO 검증 실패와 JSON 문법 오류·본문 누락은 모두 400 VALIDATION_ERROR입니다.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) {
        return error(ErrorCode.VALIDATION_ERROR);
    }

    // Service의 현재 비밀번호 오류(403), 확인 불일치(400), 회원 없음(404)과
    // 요청 횟수 초과(429)는 BusinessException에 담긴 ErrorCode로 구분합니다.
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) {
        return error(exception.getErrorCode());
    }

    // DB 자원 접근 실패나 트랜잭션 시작 불가는 일시적인 의존 시스템 문제로 보고 503을 보냅니다.
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> dependencyUnavailable(Exception ignored) {
        return error(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    // 위에서 분류하지 않은 예외는 500입니다. 성공 응답이나 쿠키 삭제로 이어지지 않습니다.
    // ignored는 원본 예외 내용을 HTTP 응답에 사용하지 않는다는 의도를 나타내는 변수명입니다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internalError(Exception ignored) {
        return error(ErrorCode.INTERNAL_ERROR);
    }

    // 상태 코드와 JSON 오류 본문을 함께 만들어 중복을 줄이는 내부 보조 메서드입니다.
    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
