package com.personalab.vectoract.vector_act_was.domain.script.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.concurrent.Executor;

/** 커밋 결과에 맞춰 OCR 처리를 시작하거나 방금 저장한 이미지를 정리합니다. */
@Component
public class ScriptJobEventListener {
    private static final Logger log = LoggerFactory.getLogger(ScriptJobEventListener.class);
    private final ScriptOcrProcessor processor;
    private final ScriptImageStorage storage;
    private final Executor executor;

    public ScriptJobEventListener(ScriptOcrProcessor processor, ScriptImageStorage storage,
                                  @Qualifier("scriptOcrExecutor") Executor executor) {
        this.processor = processor;
        this.storage = storage;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void start(ScriptJobSubmitted event) {
        executor.execute(() -> {
            try {
                processor.run(event.jobId());
            } catch (RuntimeException exception) {
                log.error("script_ocr result=ERROR jobId={} errorType={}",
                        event.jobId(), exception.getClass().getSimpleName());
            }
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void discardAfterRollback(ScriptImageStored event) {
        try {
            storage.delete(event.key());
        } catch (RuntimeException exception) {
            log.error("script_image_cleanup result=FAILED reason=ROLLED_BACK key={} errorType={}",
                    event.key(), exception.getClass().getSimpleName());
        }
    }
}
