package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.Member;

import java.time.LocalDateTime;

/**
 * 회원 응답 DTO.
 * <p>
 * Presentation 계층에서 클라이언트로 반환되는 회원 정보.
 * Entity의 민감 정보(password)를 제외하고 필요한 필드만 노출한다.
 * <p>
 * Java 21 Record를 활용하여 불변 DTO를 간결하게 정의한다.
 *
 * @param id        회원 ID
 * @param email     이메일
 * @param nickname  닉네임
 * @param role      회원 역할
 * @param createdAt 가입일시
 */
public record MemberResponse(
        Long id,
        String email,
        String nickname,
        String role,
        LocalDateTime createdAt
) {

    /**
     * Entity → DTO 변환 팩토리 메서드
     */
    public static MemberResponse from(Member member) {
        return new MemberResponse(
                member.getId(),
                member.getEmail(),
                member.getNickname(),
                member.getRole().name(),
                member.getCreatedAt()
        );
    }
}
