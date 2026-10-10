package com.personalab.vectoract.vector_act_was.domain.coaching.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SessionVideoRepository extends JpaRepository<SessionVideo, UUID> {
}
