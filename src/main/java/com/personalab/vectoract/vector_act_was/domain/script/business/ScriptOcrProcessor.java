package com.personalab.vectoract.vector_act_was.domain.script.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * 접수된 OCR 작업 하나를 처리합니다. 엔진 호출은 DB 트랜잭션 밖에서 실행해 긴 처리 중에
 * 작업 행의 잠금을 잡고 있지 않도록 합니다. 상태 전이는 Service의 개별 트랜잭션으로 확정합니다.
 *
 * <p>처리 흐름: QUEUED → (start) PROCESSING → 엔진 호출 → COMPLETED 또는 FAILED → 원본 이미지 삭제.
 * 이 클래스 자체에는 {@code @Transactional}이 없고, 각 단계(start/complete/fail/clearSource)가
 * {@link ScriptJobService}에서 각각 짧은 트랜잭션으로 커밋됩니다.
 */
@Component
public class ScriptOcrProcessor {
    private static final Logger log = LoggerFactory.getLogger(ScriptOcrProcessor.class);
    // 결과 본문 상한(1~20000자). DB·명세와 같은 코드포인트 기준입니다.
    private static final int MAX_CONTENT_CODE_POINTS = 20_000;

    private final ScriptJobService jobs;
    private final ScriptImageStorage storage;
    private final OcrEngine engine;

    public ScriptOcrProcessor(ScriptJobService jobs, ScriptImageStorage storage, OcrEngine engine) {
        this.jobs = jobs;
        this.storage = storage;
        this.engine = engine;
    }

    /** 작업 하나를 끝까지 처리합니다. 어떤 경우에도 작업은 COMPLETED/FAILED 중 하나로 확정되도록 합니다. */
    public void run(UUID jobId) {
        // QUEUED → PROCESSING 전이를 먼저 확정합니다. 이미 처리됐거나 만료된 작업이면 null을 받아 아무것도 하지 않습니다
        // (같은 작업을 두 스레드가 중복 처리하지 않게 하는 관문입니다).
        String key = jobs.start(jobId);
        if (key == null) return;
        try {
            byte[] image = storage.get(key);
            // 저장소에서 이미지를 찾지 못하면 처리할 수 없으므로 일반 실패로 기록합니다.
            if (image == null) throw new OcrFailedException(OcrFailedException.FAILED);
            String text = engine.recognize(image);
            String content = text == null ? "" : text.strip();
            if (content.isEmpty()) {
                // 글자를 하나도 인식하지 못한 경우입니다.
                jobs.fail(jobId, OcrFailedException.NO_TEXT);
            } else if (content.codePointCount(0, content.length()) > MAX_CONTENT_CODE_POINTS) {
                // 명세에 초과 처리 규칙이 없어 임의로 자르지 않고 실패로 기록합니다.
                jobs.fail(jobId, OcrFailedException.RESULT_TOO_LONG);
            } else {
                jobs.complete(jobId, content);
            }
        } catch (OcrFailedException exception) {
            // 엔진이 의도적으로 알려 준 실패 코드를 S02의 failureCode로 그대로 기록합니다.
            jobs.fail(jobId, exception.failureCode());
        } catch (RuntimeException exception) {
            // 이미지·본문이 포함될 수 있는 예외 메시지는 기록하지 않습니다.
            log.error("script_ocr result=FAILED jobId={} errorType={}", jobId, exception.getClass().getSimpleName());
            jobs.fail(jobId, OcrFailedException.FAILED);
        } finally {
            // 성공·실패와 무관하게 원본 이미지는 더 이상 필요 없습니다.
            discardSource(jobId, key);
        }
    }

    // 결과를 확정한 뒤 원본 이미지는 더 필요하지 않아 즉시 지웁니다. 실패하면 만료 정리가 다시 시도합니다.
    // (저장소 삭제가 성공한 뒤에만 DB의 key를 비웁니다. 순서가 바뀌면 삭제 실패 시 key를 잃어 객체가 고아가 됩니다.)
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
