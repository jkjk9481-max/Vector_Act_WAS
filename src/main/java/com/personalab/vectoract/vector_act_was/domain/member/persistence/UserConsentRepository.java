package com.personalab.vectoract.vector_act_was.domain.member.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserConsentRepository extends JpaRepository<UserConsent, UUID> {

    List<UserConsent> findAllByUserId(UUID userId);
    // Hard Delete에서 부모 users 행보다 먼저 자식 데이터를 제거합니다.
    void deleteByUserId(UUID userId);
}
