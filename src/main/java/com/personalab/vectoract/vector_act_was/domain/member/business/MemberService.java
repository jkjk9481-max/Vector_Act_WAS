package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.Member;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.MemberRepository;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.MemberResponse;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 비즈니스 서비스.
 * <p>
 * Business 계층에 위치하며, 트랜잭션 관리와 비즈니스 로직을 담당한다.
 * Persistence 계층의 Repository를 통해 데이터에 접근하고,
 * Presentation 계층에는 DTO(Record)로 변환하여 반환한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;

    /**
     * 회원 ID로 회원 정보 조회
     *
     * @param memberId 조회할 회원 ID
     * @return 회원 응답 DTO
     * @throws BusinessException 회원이 존재하지 않는 경우
     */
    public MemberResponse getMember(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        return MemberResponse.from(member);
    }

    /**
     * 이메일로 회원 정보 조회
     *
     * @param email 조회할 이메일
     * @return 회원 응답 DTO
     * @throws BusinessException 회원이 존재하지 않는 경우
     */
    public MemberResponse getMemberByEmail(String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        return MemberResponse.from(member);
    }
}
