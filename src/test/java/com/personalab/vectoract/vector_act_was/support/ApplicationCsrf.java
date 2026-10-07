package com.personalab.vectoract.vector_act_was.support;

import com.personalab.vectoract.vector_act_was.global.auth.ExpiringCsrfTokenRepository;
import org.springframework.security.test.web.support.WebTestUtils;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.support.WebApplicationContextUtils;

public final class ApplicationCsrf {
    private ApplicationCsrf() {}

    public static RequestPostProcessor applicationCsrf() {
        return request -> {
            // Spring Security의 csrf() 도우미는 필터의 저장소를 테스트용 기본 구현으로 교체합니다.
            // 실제 A01을 검증할 때는 교체된 저장소를 복원합니다. 토큰을 만들거나 검증을 우회하지 않습니다.
            var context = WebApplicationContextUtils.getRequiredWebApplicationContext(request.getServletContext());
            WebTestUtils.setCsrfTokenRepository(request, context.getBean(ExpiringCsrfTokenRepository.class));
            return request;
        };
    }
}
