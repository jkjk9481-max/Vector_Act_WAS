package com.personalab.vectoract.vector_act_was.domain.dashboard.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface DashboardSummaryRepository extends JpaRepository<DashboardSummary, UUID> {
    /** D05~D06: 회원·기간 유형·기간 시작일 기준으로 캐시된 기간 피드백을 조회합니다. */
    Optional<DashboardSummary> findByUserIdAndPeriodTypeAndPeriodStart(
            UUID userId, DashboardSummary.PeriodType periodType, LocalDate periodStart);
}
