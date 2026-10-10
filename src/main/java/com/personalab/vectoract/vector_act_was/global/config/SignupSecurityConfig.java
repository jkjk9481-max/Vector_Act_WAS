package com.personalab.vectoract.vector_act_was.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import com.personalab.vectoract.vector_act_was.global.auth.ExpiringCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import com.personalab.vectoract.vector_act_was.global.common.response.ErrorResponse;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import tools.jackson.databind.ObjectMapper;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

// Spring 설정 클래스입니다. @Bean 메서드의 결과를 Spring이 관리하는 객체로 등록합니다.
@Configuration
public class SignupSecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                                             AccessTokenProvider accessTokenProvider,
                                             ExpiringCsrfTokenRepository csrfTokens) throws Exception {
        // 기존 공개 API 목록을 접근 허용 설정과 인증 필터가 함께 사용합니다.
        var paths = PathPatternRequestMatcher.withDefaults();
        var publicRequests = new OrRequestMatcher(
                paths.matcher(HttpMethod.GET, "/api/auth/csrf"),
                paths.matcher(HttpMethod.POST, "/api/auth/signup"),
                paths.matcher(HttpMethod.POST, "/api/auth/login"),
                // 비밀번호를 잊은 비로그인 사용자용입니다. Bearer 없이 허용하되 CSRF 검사는 유지합니다.
                paths.matcher(HttpMethod.POST, "/api/auth/password-reset-requests"),
                paths.matcher(HttpMethod.POST, "/api/auth/password-resets"),
                // 이메일 인증 링크는 비로그인 브라우저에서도 열 수 있어 Bearer 없이 CSRF만 요구합니다.
                paths.matcher(HttpMethod.POST, "/api/users/me/email-changes"),
                paths.matcher(HttpMethod.POST, "/api/auth/refresh"),
                paths.matcher(HttpMethod.POST, "/api/auth/logout"));
        // SecurityFilterChain은 Controller에 도달하기 전에 요청의 보안 조건을 검사합니다.
        // CSRF는 다른 사이트에서 사용자 몰래 보내는 요청을 막는 검사입니다.
        // A01에서 발급한 CSRF 토큰을 세션에 보관합니다. POST 요청은 같은 세션과 토큰이 필요합니다.
        // permitAll은 로그인 전 접근을 허용한다는 뜻이며 CSRF 검증을 생략한다는 뜻은 아닙니다.
        // A07~A09는 쿠키/세션 대신 명시적인 Bearer Access Token으로 인증합니다.
        // 브라우저가 자동으로 붙이는 쿠키만으로는 인증할 수 없으므로 아래 지정된 요청만 CSRF를 제외합니다.
        // CSRF 검사 제외는 인증 제외가 아닙니다. 공통 AccessTokenAuthenticationFilter의 검증은 그대로 필요합니다.
        // 경로와 HTTP 메서드를 모두 한정하므로 로그인/재발급/로그아웃의 CSRF 검사는 유지됩니다.
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        .ignoringRequestMatchers(paths.matcher(HttpMethod.DELETE, "/api/users/me"),
                                paths.matcher(HttpMethod.PATCH, "/api/users/me"),
                                paths.matcher(HttpMethod.PATCH, "/api/users/me/password"),
                                paths.matcher(HttpMethod.POST, "/api/users/me/email-change-requests"),
                                paths.matcher(HttpMethod.PUT, "/api/users/me/profile-image"),
                                paths.matcher(HttpMethod.DELETE, "/api/users/me/profile-image"),
                                // S01은 Bearer 전용 multipart 업로드입니다. S02(GET)는 CSRF 검사 대상이 아닙니다.
                                paths.matcher(HttpMethod.POST, "/api/script-extractions"),
                                // C01은 Bearer와 Idempotency-Key로 요청하는 JSON API입니다.
                                paths.matcher(HttpMethod.POST, "/api/coaching-sessions"),
                                // C02도 Bearer 전용 JSON API입니다.
                                paths.matcher(HttpMethod.POST, "/api/coaching-sessions/{sessionId}/start"),
                                // C03도 Bearer 전용입니다. 본문이 없는 POST라 CSRF 토큰 헤더를 요구하지 않습니다.
                                paths.matcher(HttpMethod.POST, "/api/coaching-sessions/{sessionId}/connection-tickets"),
                                // C04도 Bearer 전용 JSON API입니다.
                                paths.matcher(HttpMethod.POST,
                                        "/api/coaching-sessions/{sessionId}/chunks/{chunkIndex}/upload-url"),
                                // C05도 Bearer 전용이며 본문이 없는 POST입니다.
                                paths.matcher(HttpMethod.POST,
                                        "/api/coaching-sessions/{sessionId}/chunks/{chunkIndex}/complete"),
                                // /api/auth 아래에 있지만 공개 API가 아닙니다. publicRequests에는 추가하지 않습니다.
                                paths.matcher(HttpMethod.POST, "/api/auth/reauth")))
                // CSRF 실패는 Controller 전에 발생하므로 공통 예외 처리기가 아닌 필터에서 JSON을 만듭니다.
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    var body = exception instanceof CsrfException
                            // CSRF 때문에 막힘
                            ? ErrorResponse.of(ErrorCode.CSRF_INVALID)
                            //  다른 이유로 접근 거부
                            : ErrorResponse.of(ErrorCode.ACCESS_DENIED);
                    objectMapper.writeValue(response.getOutputStream(), body);
                }))
                // ->는 람다 문법입니다. 전달받은 설정 객체(auth)에 적용할 규칙을 적습니다.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(publicRequests).permitAll()
                        .anyRequest().authenticated()); // 나머지 요청은 인증된 사용자만 허용합니다.
        // 공통 Access Token 검증을 익명 사용자 처리보다 먼저 실행합니다.
        // 유효한 토큰이면 인증 정보가 설정되어 위의 authenticated() 접근 조건을 통과합니다.
        // CSRF 설정과 실행 순서는 유지합니다. GET은 기본 CSRF 검사 대상이 아닙니다.
        http.addFilterBefore(new AccessTokenAuthenticationFilter(accessTokenProvider, objectMapper, publicRequests),
                AnonymousAuthenticationFilter.class);
        // 위 규칙과 인증 필터를 적용한 필터 체인을 완성해 반환합니다.
        return http.build();
    }
}
