package com.personalab.vectoract.vector_act_was.global.auth;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class AccessTokenAuthenticationFilterTests {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void clearsContextEvenWhenDownstreamThrows() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var provider = new AccessTokenProvider(Base64.getEncoder().encodeToString(key), "test");
        var filter = new AccessTokenAuthenticationFilter(provider, new ObjectMapper(), request -> false);
        UUID userId = UUID.randomUUID();
        var request = new MockHttpServletRequest("GET", "/api/another-protected-api");
        request.addHeader("Authorization", "Bearer " + provider.issue(userId));
        // HTTP 응답만으로 보이지 않는 필터 실행 중의 principal과 예외 발생 후 정리까지 확인합니다.
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(userId);
            throw new ServletException("downstream failure");
        })).isInstanceOf(ServletException.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void publicRequestSkipsTokenValidationEvenWithMalformedHeader() throws Exception {
        var filter = new AccessTokenAuthenticationFilter(null, new ObjectMapper(), request -> true);
        var request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        request.addHeader("Authorization", "Bearer invalid");
        var proceeded = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> proceeded.set(true));
        assertThat(proceeded).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
