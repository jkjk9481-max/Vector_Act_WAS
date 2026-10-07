package com.personalab.vectoract.vector_act_was.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A01의 발급 요청을 IP별로 제한합니다. 로그인 제한과 알고리즘만 공유하고 카운터는 분리합니다.
 * Controller 실행 전에 검사하여 제한 초과 요청이 토큰이나 세션을 새로 발급받지 않게 합니다.
 */
@Component
public class CsrfRateLimitInterceptor implements HandlerInterceptor {
    private final LoginRateLimiter limiter;

    public CsrfRateLimitInterceptor(
            @Value("${auth.csrf-rate-limit.max-attempts:60}") int maxAttempts,
            @Value("${auth.csrf-rate-limit.window-seconds:60}") long windowSeconds) {
        this.limiter = new LoginRateLimiter(maxAttempts, windowSeconds);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("GET".equals(request.getMethod())) {
            // 세션 ID로 제한하면 새 세션을 계속 만들어 우회할 수 있어 서버가 확인한 IP를 사용합니다.
            // 위조 가능한 X-Forwarded-For를 직접 사용하지 않습니다.
            // 제한 초과 시 BusinessException을 공통 예외 처리기가 429 RATE_LIMITED로 변환합니다.
            limiter.acquire(request.getRemoteAddr());
        }
        return true;
    }
}
