package com.personalab.vectoract.vector_act_was.global.auth;

import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginRateLimiterTests {
    @Test
    void limitsPerAddressAndResetsAtWindowEnd() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        var limiter = new LoginRateLimiter(2, 60, clock);
        limiter.acquire("a");
        limiter.acquire("a");
        assertThatThrownBy(() -> limiter.acquire("a")).isInstanceOf(BusinessException.class);
        assertThatCode(() -> limiter.acquire("b")).doesNotThrowAnyException();
        when(clock.millis()).thenReturn(60_000L);
        assertThatCode(() -> limiter.acquire("a")).doesNotThrowAnyException();
    }

    @Test
    void concurrentRequestsCannotBypassLimit() throws Exception {
        var limiter = new LoginRateLimiter(10, 60, Clock.systemUTC());
        AtomicInteger allowed = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 50; i++) {
                tasks.add(executor.submit(() -> {
                    try { limiter.acquire("same-ip"); allowed.incrementAndGet(); }
                    catch (BusinessException expected) { }
                }));
            }
            for (var task : tasks) task.get();
        }
        assertThat(allowed.get()).isEqualTo(10);
    }
}
