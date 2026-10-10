package com.personalab.vectoract.vector_act_was.domain.script.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 접수 24시간이 지난 OCR 작업과 남은 임시 이미지를 주기적으로 정리합니다(DB 설계서 삭제 정책).
 *
 * <p>{@code @ConditionalOnProperty}: {@code script.ocr.purge-enabled=false}이면 이 빈 자체를 만들지 않습니다.
 * 테스트에서 정리 스케줄러가 데이터를 지우지 않도록 끌 때 사용하고, 설정이 없으면 켜진 상태가 기본입니다.
 */
@Component
@ConditionalOnProperty(name = "script.ocr.purge-enabled", havingValue = "true", matchIfMissing = true)
public class ScriptJobPurgeScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScriptJobPurgeScheduler.class);
    private final ScriptJobService service;

    public ScriptJobPurgeScheduler(ScriptJobService service) {
        this.service = service;
    }

    /**
     * fixedDelay: 이전 실행이 끝난 뒤 지정한 시간이 지나면 다음 실행을 시작합니다(겹쳐 실행되지 않습니다).
     * initialDelay: 서버 기동 직후 부하를 피하려고 첫 실행을 늦춥니다.
     */
    @Scheduled(fixedDelayString = "${script.ocr.purge-delay-ms:3600000}",
            initialDelayString = "${script.ocr.purge-initial-delay-ms:120000}")
    public void purgeExpired() {
        // 만료 작업 ID만 먼저 읽고, 작업마다 별도 트랜잭션(service.purge)으로 정리합니다.
        // 한 작업의 저장소 삭제 실패가 다른 작업의 정리를 롤백시키지 않게 하기 위해서입니다.
        for (var id : service.findExpiredIds()) {
            try {
                if (service.purge(id)) log.info("script_job_purge result=SUCCESS jobId={}", id);
            } catch (RuntimeException exception) {
                // 실패해도 행이 남아 다음 주기에 다시 시도됩니다.
                log.error("script_job_purge result=RETRY jobId={} errorType={}",
                        id, exception.getClass().getSimpleName());
            }
        }
    }
}
