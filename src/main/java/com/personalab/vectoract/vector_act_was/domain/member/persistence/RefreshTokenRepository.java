package com.personalab.vectoract.vector_act_was.domain.member.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
}
