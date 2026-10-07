package com.personalab.vectoract.vector_act_was.global.auth;

import com.personalab.vectoract.vector_act_was.domain.member.business.AccountAccessService;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.UUID;

/** 서명이 유효한 JWT라도 계정이 탈퇴했으면 모든 보호 MVC API를 차단합니다. */
@Component
public class AccountStatusInterceptor implements HandlerInterceptor {
    private final AccountAccessService service;
    public AccountStatusInterceptor(AccountAccessService service) {
        this.service = service;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // 공개 로그인/재발급 API는 UUID 인증 주체가 없으며 각 서비스에서 상태를 확인합니다.
        if (authentication != null && authentication.getPrincipal() instanceof UUID userId) {
            // 인터셉터는 HTTP 요청 종류만 구분합니다. DB 조회와 접근 정책은 Service에 맡깁니다.
            // contextPath를 제외하므로 애플리케이션이 하위 경로에 배포되어도 같은 API를 인식합니다.
            boolean withdrawal = "DELETE".equals(request.getMethod())
                    && "/api/users/me".equals(request.getRequestURI().substring(request.getContextPath().length()));
            service.requireActiveAccount(userId, withdrawal);
        }
        return true;
    }
}
