package com.personalab.vectoract.vector_act_was.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

// Spring 설정 클래스입니다. @Bean 메서드의 결과를 Spring이 관리하는 객체로 등록합니다.
@Configuration
public class SignupSecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // SecurityFilterChain은 Controller에 도달하기 전에 요청의 보안 조건을 검사합니다.
        // CSRF는 다른 사이트에서 사용자 몰래 보내는 요청을 막는 검사입니다.
        // 회원가입과 로그인은 인증 전에 호출합니다. CSRF 예외도 두 POST 경로에만 적용합니다.
        http.csrf(csrf -> csrf.ignoringRequestMatchers(
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/signup"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login")))
                // ->는 람다 문법입니다. 전달받은 설정 객체(auth)에 적용할 규칙을 적습니다.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login").permitAll()
                        .anyRequest().authenticated()); // 나머지 요청은 인증된 사용자만 허용합니다.
        // 위 규칙을 적용한 필터 체인을 완성해 반환합니다.
        return http.build();
    }
}
