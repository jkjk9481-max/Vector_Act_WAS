package com.personalab.vectoract.vector_act_was.domain.member.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserConsentRepository extends JpaRepository<UserConsent, UUID> {

    List<UserConsent> findAllByUserId(UUID userId);
}
