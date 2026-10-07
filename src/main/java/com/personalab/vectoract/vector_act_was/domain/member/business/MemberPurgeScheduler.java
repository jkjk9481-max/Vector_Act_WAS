package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import java.time.*;

/** 기본 1시간마다 7일 보관 기간이 끝난 계정을 찾습니다. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "auth.withdrawal.purge-enabled", havingValue = "true", matchIfMissing = true)
public class MemberPurgeScheduler {
    private static final Logger log = LoggerFactory.getLogger(MemberPurgeScheduler.class);
    private final UserRepository users;
    private final MemberPurgeService service;
    public MemberPurgeScheduler(UserRepository users, MemberPurgeService service) {
        this.users = users;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${auth.withdrawal.purge-delay-ms:3600000}",
            initialDelayString = "${auth.withdrawal.purge-initial-delay-ms:60000}")
    public void purgeDueUsers() {
        // 먼저 ID 목록을 확보하여 삭제 때문에 페이지가 당겨져 대상이 누락되는 일을 방지합니다.
        var ids = users.findPurgeCandidates(OffsetDateTime.now(ZoneOffset.UTC));
        for (var id : ids) {
            try {
                if (service.purge(id)) log.info("member_purge result=SUCCESS userId={}", id);
            } catch (RuntimeException exception) {
                // 실패해도 WITHDRAWN/purge_at이 남으므로 다음 주기에 자동 재시도됩니다.
                // 저장소 예외 메시지에 파일 경로나 비밀 정보가 있을 수 있어 예외 종류만 기록합니다.
                log.error("member_purge result=RETRY userId={} errorType={}", id, exception.getClass().getSimpleName());
            }
        }
    }
}
