package com.personalab.vectoract.vector_act_was.domain.member.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserConsentRepository extends JpaRepository<UserConsent, UUID> {

    List<UserConsent> findAllByUserId(UUID userId);
    // Hard Delete에서 부모 users 행보다 먼저 자식 데이터를 제거합니다.
    // 약관 동의도 보관 기간에는 유지합니다. 영상 저장소 삭제 성공 후 Service가 호출합니다.
    // 회원을 참조하는 외래 키가 있으므로 users 행보다 먼저 삭제해야 합니다.
    void deleteByUserId(UUID userId);
}
