package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.CsrfResponse;
import com.personalab.vectoract.vector_act_was.global.common.response.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A01: 로그인 전에도 호출할 수 있는 CSRF 토큰 발급 API입니다. */
@RestController
public class CsrfController {
    @GetMapping("/api/auth/csrf")
    public ResponseEntity<ApiResponse<CsrfResponse>> csrf(CsrfToken csrfToken) {
        // Spring Security가 만든 토큰을 사용합니다. 직접 난수를 만들면 세션의 검증값과 달라집니다.
        // getToken()이 지연된 토큰 생성을 실행하고 세션에 저장합니다.
        // 클라이언트는 응답의 token을 headerName 헤더에 넣고, 발급 때의 세션 쿠키도 함께 보냅니다.
        var data = new CsrfResponse(csrfToken.getToken(), csrfToken.getHeaderName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
