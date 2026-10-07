package com.personalab.vectoract.vector_act_was.global.auth;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.UUID;

/** 서명이 유효한 JWT라도 계정이 탈퇴했으면 모든 보호 MVC API를 차단합니다. */
@Component
public class AccountStatusInterceptor implements HandlerInterceptor {
    private final UserRepository users;
    public AccountStatusInterceptor(UserRepository users) { this.users = users; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // 공개 로그인/재발급 API는 UUID 인증 주체가 없으며 각 서비스에서 상태를 확인합니다.
        if (authentication != null && authentication.getPrincipal() instanceof UUID userId) {
            var user = users.findById(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (user.getAccountStatus() != User.AccountStatus.ACTIVE) {
                boolean withdrawal = "DELETE".equals(request.getMethod())
                        && "/api/users/me".equals(request.getRequestURI().substring(request.getContextPath().length()));
                // A10 재요청만 409입니다. 다른 API는 기존 회원 조회 규칙인 404를 유지합니다.
                throw new BusinessException(withdrawal ? ErrorCode.ACCOUNT_DELETED : ErrorCode.RESOURCE_NOT_FOUND);
            }
        }
        return true;
    }
}
