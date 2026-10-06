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
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * 보호 API의 Authorization: Bearer <accessToken> 헤더를 검사하는 공통 인증 필터입니다.
 * 흐름: 대상 경로 확인 → 헤더 검사 → JWT 검증 → 인증 정보 저장 → 다음 필터/컨트롤러 실행.
 * OncePerRequestFilter는 같은 요청 디스패치에서 필터가 중복 실행되지 않도록 관리합니다.
 * 이 클래스는 보안 설정에서 직접 생성하여 SecurityFilterChain에만 등록합니다.
 */
public class AccessTokenAuthenticationFilter extends OncePerRequestFilter {
    private final RequestMatcher publicRequests;
    private final AccessTokenProvider tokens;
    // Java 오류 응답 객체를 JSON으로 변환하는 도구입니다.
    private final ObjectMapper objectMapper;

    public AccessTokenAuthenticationFilter(AccessTokenProvider tokens, ObjectMapper objectMapper,
                                           RequestMatcher publicRequests) {
        this.tokens = tokens;
        this.objectMapper = objectMapper;
        this.publicRequests = publicRequests;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // permitAll과 같은 matcher를 공유하여 공개 경로 목록이 서로 어긋나지 않도록 합니다.
        // 공개 API는 Authorization 헤더 내용과 관계없이 기존 인증/CSRF 흐름을 유지합니다.
        return publicRequests.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 1. 헤더가 없으면 401 AUTH_REQUIRED를 보내고 return으로 이후 처리를 중단합니다.
        String header = request.getHeader("Authorization");
        if (header == null) {
            reject(response, ErrorCode.AUTH_REQUIRED);
            return;
        }
        // 2. regionMatches의 true는 대소문자를 무시한다는 뜻입니다. 앞의 7글자가 "Bearer "인지 봅니다.
        // ||는 앞 조건이 참이면 뒤 조건을 실행하지 않으므로 짧은 헤더에도 substring(7)을 호출하지 않습니다.
        // 접두사가 맞아도 뒤에 실제 토큰이 없으면 401 ACCESS_INVALID입니다.
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7) || header.substring(7).isBlank()) {
            reject(response, ErrorCode.ACCESS_INVALID);
            return;
        }
        UUID userId;
        try {
            // 3. 접두사를 뺀 JWT의 서명·만료·발급자·용도 등을 기존 AccessTokenProvider로 검증합니다.
            // subject(sub)는 토큰 발급 시 저장한 회원 ID입니다. 검증 후 UUID로 변환합니다.
            userId = UUID.fromString(tokens.verify(header.substring(7)).getSubject());
        } catch (JwtValidationException exception) {
            // 만료 이외에 발급자/용도/subject 오류도 있으면 ACCESS_INVALID를 우선합니다.
            boolean expiredOnly = !exception.getErrors().isEmpty() && exception.getErrors().stream()
                    .allMatch(error -> AccessTokenProvider.EXPIRED_ERROR.equals(error.getErrorCode()));
            reject(response, expiredOnly ? ErrorCode.ACCESS_EXPIRED : ErrorCode.ACCESS_INVALID);
            return;
        } catch (JwtException | IllegalArgumentException exception) {
            // 검증 실패나 잘못된 UUID는 인증 실패입니다. 토큰 원문은 오류 응답에 포함하지 않습니다.
            reject(response, ErrorCode.ACCESS_INVALID);
            return;
        }
        // 4. SecurityContext는 현재 요청의 인증 정보를 담습니다. principal(인증 주체)에 회원 UUID를 넣습니다.
        // 아래 클래스명에 Password가 있지만 여기서는 비밀번호 로그인을 하지 않고 검증 결과를 담는 용도로 씁니다.
        // 세 인자 생성자는 인증 완료 상태를 만듭니다. 비밀번호는 null, 별도 역할/권한 목록은 빈 목록입니다.
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            // 5. 다음 보안 필터로 넘깁니다. 접근 검사를 통과하면 요청 경로의 컨트롤러가 실행됩니다.
            chain.doFilter(request, response);
        } finally {
            // 성공/예외와 관계없이 인증 정보를 정리하여 재사용되는 스레드에 회원 정보가 남지 않게 합니다.
            SecurityContextHolder.clearContext();
        }
    }

    private void reject(HttpServletResponse response, ErrorCode code) throws IOException {
        // 필터는 컨트롤러보다 앞에서 실행되므로 GlobalExceptionHandler 대신 직접 JSON 오류를 작성합니다.
        SecurityContextHolder.clearContext();
        response.setStatus(code.getStatus().value());
        // 클라이언트에 이 API가 Bearer 인증을 요구한다는 것을 알립니다.
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code));
    }
}
