package com.personalab.vectoract.vector_act_was.global.auth;

import com.personalab.vectoract.vector_act_was.global.common.response.ErrorResponse;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** A06은 세션 대신 매 요청의 Bearer Access Token으로 인증합니다. */
public class MeAuthenticationFilter extends OncePerRequestFilter {
    private final AccessTokenProvider tokens;
    private final ObjectMapper objectMapper;

    public MeAuthenticationFilter(AccessTokenProvider tokens, ObjectMapper objectMapper) {
        this.tokens = tokens;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod())
                || !(request.getContextPath() + "/api/users/me").equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null) {
            reject(response, ErrorCode.UNAUTHORIZED);
            return;
        }
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7) || header.substring(7).isBlank()) {
            reject(response, ErrorCode.INVALID_TOKEN);
            return;
        }
        UUID userId;
        try {
            userId = UUID.fromString(tokens.verify(header.substring(7)).getSubject());
        } catch (JwtException | IllegalArgumentException exception) {
            reject(response, ErrorCode.INVALID_TOKEN);
            return;
        }
        // 검증된 토큰의 회원 ID만 현재 요청의 인증 정보로 사용합니다.
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void reject(HttpServletResponse response, ErrorCode code) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(code.getStatus().value());
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code));
    }
}
