package com.personalab.vectoract.vector_act_was.domain.script.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 접수 24시간이 지난 OCR 작업과 남은 임시 이미지를 주기적으로 정리합니다. */
@Component
@ConditionalOnProperty(name = "script.ocr.purge-enabled", havingValue = "true", matchIfMissing = true)
public class ScriptJobPurgeScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScriptJobPurgeScheduler.class);
    private final ScriptJobService service;

    public ScriptJobPurgeScheduler(ScriptJobService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${script.ocr.purge-delay-ms:3600000}",
            initialDelayString = "${script.ocr.purge-initial-delay-ms:120000}")
    public void purgeExpired() {
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
