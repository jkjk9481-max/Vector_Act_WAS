package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.MemberService;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.MemberResponse;
import com.personalab.vectoract.vector_act_was.global.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 API 컨트롤러.
 * <p>
 * Presentation 계층에 위치하며, HTTP 요청/응답 처리만 담당한다.
 * 비즈니스 로직은 {@link MemberService}에 위임한다.
 */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    /**
     * 회원 정보 조회
     *
     * @param memberId 조회할 회원 ID
     * @return 회원 정보 응답
     */
    @GetMapping("/{memberId}")
    public ResponseEntity<ApiResponse<MemberResponse>> getMember(
            @PathVariable Long memberId) {

        MemberResponse response = memberService.getMember(memberId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
