package com.personalab.vectoract.vector_act_was.global.config;

import com.personalab.vectoract.vector_act_was.global.auth.LoginRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class LoginWebConfig implements WebMvcConfigurer {
    private final LoginRateLimiter limiter;
    private final com.personalab.vectoract.vector_act_was.global.auth.AccountStatusInterceptor accountStatus;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accountStatus).addPathPatterns("/**");
        // 회원가입/다른 API의 동작을 바꾸지 않고 로그인 요청만 제한합니다.
        registry.addInterceptor(limiter).addPathPatterns("/api/auth/login");
    }
}
