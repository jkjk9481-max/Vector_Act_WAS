package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.MeService;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.MeResponse;
import com.personalab.vectoract.vector_act_was.global.common.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class MeController {
    private final MeService service;

    public MeController(MeService service) {
        this.service = service;
    }

    @GetMapping("/api/users/me")
    public ResponseEntity<ApiResponse<MeResponse>> me(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ApiResponse.ok(service.getMe(userId)));
    }
}
