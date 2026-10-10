package com.personalab.vectoract.vector_act_was.domain.script.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * OCR 처리에 필요한 기본 빈(엔진, 이미지 저장소, 실행기)을 등록하는 설정 클래스입니다.
 * 각 빈은 "더 알맞은 구현이 있으면 그것을 쓰고, 없을 때만 기본값을 쓴다"는 조건부 등록입니다.
 */
@Configuration
public class ScriptOcrConfig {
    private static final Logger log = LoggerFactory.getLogger(ScriptOcrConfig.class);

    // 실제 구현체가 Bean으로 등록되면 아래 임시 구현은 사용되지 않습니다.
    // @ConditionalOnMissingBean: 컨텍스트에 OcrEngine 빈이 아직 없을 때만 이 메서드가 실행됩니다.
    @Bean
    @ConditionalOnMissingBean(OcrEngine.class)
    OcrEngine unavailableOcrEngine() {
        log.warn("ocr_engine type=UNAVAILABLE (OCR jobs will fail with OCR_ENGINE_UNAVAILABLE)");
        return new UnavailableOcrEngine();
    }

    // S3 버킷이 설정되면 S3ScriptImageStorage가 사용되고, 아니면 임시 메모리 구현을 씁니다.
    // @ConditionalOnExpression: 속성 storage.s3.bucket이 비어 있는지(SpEL)로 등록 여부를 결정합니다.
    // 두 구현이 같은 조건의 정반대로 등록되므로 ScriptImageStorage 빈은 항상 정확히 하나입니다.
    @Bean
    @ConditionalOnExpression("'${storage.s3.bucket:}'.isEmpty()")
    InMemoryScriptImageStorage inMemoryScriptImageStorage() {
        log.warn("script_image_storage type=IN_MEMORY (not for production)");
        return new InMemoryScriptImageStorage();
    }

    /**
     * OCR 작업을 요청 스레드와 분리해 실행합니다. 대기열이 가득 차면 호출 스레드가 직접 처리해 작업을 잃지 않습니다.
     * 서버 재시작 시 메모리 대기열은 사라지며 자동 복구는 없습니다. async=false는 테스트에서 동기 실행에 사용합니다.
     *
     * <p>스레드 풀 구성: 동시에 2개까지 처리하고, 초과분은 100개까지 대기열에 쌓습니다.
     * 그마저 가득 차면 CallerRunsPolicy가 작업을 거절하는 대신 요청 스레드가 직접 실행하게 해(속도가 느려질 뿐)
     * 작업이 유실되지 않습니다.
     */
    @Bean(name = "scriptOcrExecutor")
    Executor scriptOcrExecutor(@Value("${script.ocr.async:true}") boolean async) {
        // 동기 모드: 람다 Runnable::run은 "받은 작업을 지금 이 스레드에서 바로 실행"하는 Executor입니다.
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
