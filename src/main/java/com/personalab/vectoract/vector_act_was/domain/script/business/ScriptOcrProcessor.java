package com.personalab.vectoract.vector_act_was.domain.script.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * 접수된 OCR 작업 하나를 처리합니다. 엔진 호출은 DB 트랜잭션 밖에서 실행해 긴 처리 중에
 * 작업 행의 잠금을 잡고 있지 않도록 합니다. 상태 전이는 Service의 개별 트랜잭션으로 확정합니다.
 */
@Component
public class ScriptOcrProcessor {
    private static final Logger log = LoggerFactory.getLogger(ScriptOcrProcessor.class);
    private static final int MAX_CONTENT_CODE_POINTS = 20_000;

    private final ScriptJobService jobs;
    private final ScriptImageStorage storage;
    private final OcrEngine engine;

    public ScriptOcrProcessor(ScriptJobService jobs, ScriptImageStorage storage, OcrEngine engine) {
        this.jobs = jobs;
        this.storage = storage;
        this.engine = engine;
    }

    public void run(UUID jobId) {
        String key = jobs.start(jobId);
        if (key == null) return;
        try {
            byte[] image = storage.get(key);
            if (image == null) throw new OcrFailedException(OcrFailedException.FAILED);
            String text = engine.recognize(image);
            String content = text == null ? "" : text.strip();
            if (content.isEmpty()) {
                jobs.fail(jobId, OcrFailedException.NO_TEXT);
            } else if (content.codePointCount(0, content.length()) > MAX_CONTENT_CODE_POINTS) {
                // 명세에 초과 처리 규칙이 없어 임의로 자르지 않고 실패로 기록합니다.
                jobs.fail(jobId, OcrFailedException.RESULT_TOO_LONG);
            } else {
                jobs.complete(jobId, content);
            }
        } catch (OcrFailedException exception) {
            jobs.fail(jobId, exception.failureCode());
        } catch (RuntimeException exception) {
            // 이미지·본문이 포함될 수 있는 예외 메시지는 기록하지 않습니다.
            log.error("script_ocr result=FAILED jobId={} errorType={}", jobId, exception.getClass().getSimpleName());
            jobs.fail(jobId, OcrFailedException.FAILED);
        } finally {
            discardSource(jobId, key);
        }
    }

    // 결과를 확정한 뒤 원본 이미지는 더 필요하지 않아 즉시 지웁니다. 실패하면 만료 정리가 다시 시도합니다.
    private void discardSource(UUID jobId, String key) {
        try {
            storage.delete(key);
            jobs.clearSource(jobId);
        } catch (RuntimeException exception) {
            log.warn("script_ocr_cleanup result=RETRY_AT_EXPIRY jobId={} errorType={}",
                    jobId, exception.getClass().getSimpleName());
        }
    }
}
