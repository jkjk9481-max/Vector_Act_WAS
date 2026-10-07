package com.personalab.vectoract.vector_act_was.domain.member.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

/**
 * HTTP 요청 대신 시간표에 의해 실행되는 진입점입니다.
 * 웹 요청의 Controller 역할에 해당하며, Scheduler → Service → Repository 순서로 호출합니다.
 * DB에 접근하거나 삭제 정책을 판단하지 않고 Service 호출과 결과 로그만 담당합니다.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "auth.withdrawal.purge-enabled", havingValue = "true", matchIfMissing = true)
public class MemberPurgeScheduler {
    private static final Logger log = LoggerFactory.getLogger(MemberPurgeScheduler.class);
    private final MemberPurgeService service;
    public MemberPurgeScheduler(MemberPurgeService service) {
        this.service = service;
    }

    // 서버 시작 1분 후 첫 실행, 이후 이전 실행 종료로부터 1시간 후 재실행이 기본값입니다.
    // 회원별로 7일짜리 타이머를 만드는 것이 아니라, 매 실행 때 purge_at이 지난 회원을 찾습니다.
    @Scheduled(fixedDelayString = "${auth.withdrawal.purge-delay-ms:3600000}",
            initialDelayString = "${auth.withdrawal.purge-initial-delay-ms:60000}")
    public void purgeDueUsers() {
        // 먼저 ID 목록을 확보하여 삭제 때문에 페이지가 당겨져 대상이 누락되는 일을 방지합니다.
        var ids = service.findDueUserIds();
        for (var id : ids) {
            try {
                // 다른 Bean의 public 메서드를 호출해야 Spring이 회원별 트랜잭션을 시작/커밋합니다.
                // 메서드가 true를 반환해도 커밋이 실패하면 예외가 전달되어 성공 로그를 남기지 않습니다.
                if (service.purge(id)) log.info("member_purge result=SUCCESS userId={}", id);
            } catch (RuntimeException exception) {
                // 실패해도 WITHDRAWN/purge_at이 남으므로 다음 주기에 자동 재시도됩니다.
                // 저장소 예외 메시지에 파일 경로나 비밀 정보가 있을 수 있어 예외 종류만 기록합니다.
                log.error("member_purge result=RETRY userId={} errorType={}", id, exception.getClass().getSimpleName());
            }
        }
    }
}
