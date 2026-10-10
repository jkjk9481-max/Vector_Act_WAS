package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.MeService;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.MeResponse;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.UpdateMeRequest;
import jakarta.validation.Valid;
import com.personalab.vectoract.vector_act_was.global.common.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A06 내 정보 조회의 HTTP 요청과 응답을 담당합니다.
 * 흐름: AccessTokenAuthenticationFilter(토큰 검증) → MeController(요청 접수)
 * → MeService.Result(조회 결과) → 컨트롤러에서 MeResponse로 변환 → JSON 응답.
 * 회원이 없으면 서비스의 BusinessException을 GlobalExceptionHandler가 404로 변환합니다.
 */
// @RestController는 메서드 반환값을 JSON 응답 본문으로 내보내도록 합니다.
@RestController
public class MeController {
    private final MeService service;

    // 생성자 주입: Spring이 관리하는 MeService를 전달받아 사용합니다.
    public MeController(MeService service) {
        this.service = service;
    }

    // A07: 수정 대상은 요청값이 아닌 검증된 Access Token의 회원 ID로 결정합니다.
    @PatchMapping("/api/users/me")
    public ResponseEntity<ApiResponse<MeResponse>> updateMe(
            @AuthenticationPrincipal UUID userId, @Valid @RequestBody UpdateMeRequest request) {
        var result = service.updateMe(userId, request.name());
        var response = new MeResponse(result.userId(), result.name(), result.email(),
                result.createdAt(), result.profileImageUrl());
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ApiResponse.ok(response));
    }

    // GET /api/users/me 요청을 이 메서드에 연결합니다. 요청 본문이 없어 @RequestBody는 없습니다.
    // @AuthenticationPrincipal은 필터가 인증 정보에 저장한 UUID를 꺼냅니다.
    // 사용자가 보낸 쿼리 파라미터가 아니라 검증된 토큰의 ID로 본인 정보를 조회합니다.
    @GetMapping("/api/users/me")
    public ResponseEntity<ApiResponse<MeResponse>> me(@AuthenticationPrincipal UUID userId) {
        // HTTP 응답 형식으로 변환하는 책임은 presentation 계층인 컨트롤러에 둡니다.
        var result = service.getMe(userId);
        var response = new MeResponse(result.userId(), result.name(), result.email(),
                result.createdAt(), result.profileImageUrl());
        // ApiResponse.ok가 success/data/message/error로 감쌉니다.
        // ResponseEntity.ok는 HTTP 200을 지정하고, no-store는 개인정보 응답을 캐시에 저장하지 말라는 뜻입니다.
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ApiResponse.ok(response));
    }
}
