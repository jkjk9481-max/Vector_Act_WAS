package com.personalab.vectoract.vector_act_was.global.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpiringCsrfTokenRepositoryTests {
    @Test
    void expiresAtExactBoundaryAndRegeneratesWithoutExtendingExistingToken() {
        var clock = mock(Clock.class);
        var issuedAt = Instant.parse("2026-10-07T00:00:00Z");
        when(clock.instant()).thenReturn(issuedAt);
        var repository = new ExpiringCsrfTokenRepository(1800, clock);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var token = repository.loadDeferredToken(request, response).get();
        var deadline = issuedAt.plusSeconds(1800);
        assertThat(repository.getExpiresAt(request).toInstant()).isEqualTo(deadline);
        assertThat(token.getHeaderName()).isEqualTo("X-CSRF-TOKEN");

        // 새 요청을 읽어도 만료 시각은 최초 발급 기준을 유지합니다.
        when(clock.instant()).thenReturn(deadline.minusSeconds(1));
        assertThat(repository.loadDeferredToken(request, response).get().getToken()).isEqualTo(token.getToken());
        assertThat(repository.getExpiresAt(request).toInstant()).isEqualTo(deadline);

        // expiresAt과 정확히 같은 순간부터 기존 토큰을 불러오지 않습니다.
        when(clock.instant()).thenReturn(deadline);
        assertThat(repository.loadToken(request)).isNull();
        var renewed = repository.loadDeferredToken(request, response).get();
        assertThat(renewed.getToken()).isNotEqualTo(token.getToken());
        assertThat(repository.getExpiresAt(request).toInstant()).isEqualTo(deadline.plusSeconds(1800));
    }

    @Test
    void removalClearsTokenAndExpiryWithoutCreatingSession() {
        var repository = new ExpiringCsrfTokenRepository(1800);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        repository.saveToken(null, request, response);
        assertThat(request.getSession(false)).isNull();
        repository.loadDeferredToken(request, response).get();
        repository.saveToken(null, request, response);
        assertThat(repository.loadToken(request)).isNull();
        assertThatThrownBy(() -> repository.getExpiresAt(request)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void legacySessionWithoutExpiryRequiresNewToken() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var legacy = new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository();
        var previous = legacy.generateToken(request);
        legacy.saveToken(previous, request, response);
        var repository = new ExpiringCsrfTokenRepository(1800);
        assertThat(repository.loadToken(request)).isNull();
        assertThat(repository.loadDeferredToken(request, response).get().getToken()).isNotEqualTo(previous.getToken());
    }

    @Test
    void invalidLifetimeIsRejected() {
        assertThatThrownBy(() -> new ExpiringCsrfTokenRepository(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExpiringCsrfTokenRepository(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
