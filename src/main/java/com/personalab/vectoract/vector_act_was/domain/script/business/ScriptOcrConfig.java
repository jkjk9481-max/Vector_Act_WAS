package com.personalab.vectoract.vector_act_was.domain.script.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ScriptOcrConfig {
    private static final Logger log = LoggerFactory.getLogger(ScriptOcrConfig.class);

    // 실제 구현체가 Bean으로 등록되면 아래 임시 구현은 사용되지 않습니다.
    @Bean
    @ConditionalOnMissingBean(OcrEngine.class)
    OcrEngine unavailableOcrEngine() {
        log.warn("ocr_engine type=UNAVAILABLE (OCR jobs will fail with OCR_ENGINE_UNAVAILABLE)");
        return new UnavailableOcrEngine();
    }

    @Bean
    @ConditionalOnMissingBean(ScriptImageStorage.class)
    InMemoryScriptImageStorage inMemoryScriptImageStorage() {
        log.warn("script_image_storage type=IN_MEMORY (not for production)");
        return new InMemoryScriptImageStorage();
    }

    /**
     * OCR 작업을 요청 스레드와 분리해 실행합니다. 대기열이 가득 차면 호출 스레드가 직접 처리해 작업을 잃지 않습니다.
     * 서버 재시작 시 메모리 대기열은 사라지며 자동 복구는 없습니다. async=false는 테스트에서 동기 실행에 사용합니다.
     */
    @Bean(name = "scriptOcrExecutor")
    Executor scriptOcrExecutor(@Value("${script.ocr.async:true}") boolean async) {
        if (!async) return Runnable::run;
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("script-ocr-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
