package com.personalab.vectoract.vector_act_was.global.auth;

import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** 단일 서버용 로그인 요청 제한입니다. Controller의 JSON 변환/검증 전에 실행됩니다. */
@Component
public class LoginRateLimiter implements HandlerInterceptor {
    private final int maxAttempts;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    @Autowired
    public LoginRateLimiter(@Value("${auth.login-rate-limit.max-attempts:10}") int maxAttempts,
                            @Value("${auth.login-rate-limit.window-seconds:60}") long windowSeconds) {
        this(maxAttempts, windowSeconds, Clock.systemUTC());
    }

    // 테스트에서 시간을 직접 제어하여 1분을 실제로 기다리지 않고 제한 해제를 검증합니다.
    LoginRateLimiter(int maxAttempts, long windowSeconds, Clock clock) {
        if (maxAttempts < 1 || windowSeconds < 1) throw new IllegalArgumentException("Rate limit must be positive");
        this.maxAttempts = maxAttempts;
        this.windowMillis = Math.multiplyExact(windowSeconds, 1000);
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("POST".equals(request.getMethod())) {
            // 임의로 위조할 수 있는 X-Forwarded-For를 직접 믿지 않고 서버가 확인한 주소를 사용합니다.
            acquire(request.getRemoteAddr());
        }
        return true;
    }

    public synchronized void acquire(String address) {
        // synchronized는 동시 요청이 같은 횟수를 읽고 제한을 초과해 통과하는 상황을 막습니다.
        long now = clock.millis();
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        Window window = windows.get(address);
        if (window == null) {
            // 임의의 IP가 계속 들어와도 메모리가 끝없이 증가하지 않도록 상한을 둡니다.
            if (windows.size() >= 10_000) throw new BusinessException(ErrorCode.RATE_LIMITED);
            window = new Window(now + windowMillis);
            windows.put(address, window);
        }
        if (window.attempts >= maxAttempts) throw new BusinessException(ErrorCode.RATE_LIMITED);
        window.attempts++;
    }

    private static class Window {
        final long expiresAt;
        int attempts;
        Window(long expiresAt) { this.expiresAt = expiresAt; }
    }
}
