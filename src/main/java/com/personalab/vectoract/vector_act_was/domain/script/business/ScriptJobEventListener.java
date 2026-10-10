package com.personalab.vectoract.vector_act_was.domain.script.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.concurrent.Executor;

/**
 * 커밋 결과에 맞춰 OCR 처리를 시작하거나 방금 저장한 이미지를 정리합니다.
 * <ul>
 *   <li>커밋됨 → OCR 처리를 별도 스레드에서 시작합니다(요청은 처리를 기다리지 않고 202로 끝납니다).</li>
 *   <li>롤백됨 → 접수 도중 저장한 원본 이미지를 지웁니다(DB에는 작업이 없으므로 고아 객체가 됩니다).</li>
 * </ul>
 */
@Component
public class ScriptJobEventListener {
    private static final Logger log = LoggerFactory.getLogger(ScriptJobEventListener.class);
    private final ScriptOcrProcessor processor;
    private final ScriptImageStorage storage;
    // OCR 전용 스레드 풀(ScriptOcrConfig). 같은 타입 Executor가 여럿일 수 있어 이름으로 지정합니다.
    private final Executor executor;

    public ScriptJobEventListener(ScriptOcrProcessor processor, ScriptImageStorage storage,
                                  @Qualifier("scriptOcrExecutor") Executor executor) {
        this.processor = processor;
        this.storage = storage;
        this.executor = executor;
    }

    /** 작업 접수가 커밋된 뒤 호출됩니다. 실제 처리는 풀의 스레드에서 실행됩니다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void start(ScriptJobSubmitted event) {
        executor.execute(() -> {
            try {
                processor.run(event.jobId());
            } catch (RuntimeException exception) {
                // 풀 스레드에서 올라온 예외는 호출자가 없어 사라지므로 여기서 기록합니다. 이미지·본문은 남기지 않습니다.
                log.error("script_ocr result=ERROR jobId={} errorType={}",
                        event.jobId(), exception.getClass().getSimpleName());
            }
        });
    }

    /** 접수 트랜잭션이 롤백된 뒤 호출됩니다. 방금 올린 이미지를 가리키는 작업이 없으므로 지웁니다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void discardAfterRollback(ScriptImageStored event) {
        try {
            storage.delete(event.key());
        } catch (RuntimeException exception) {
            // 삭제 실패는 요청 결과를 바꿀 수 없습니다. key를 남겨 나중에 수동으로 정리할 수 있게 합니다.
            log.error("script_image_cleanup result=FAILED reason=ROLLED_BACK key={} errorType={}",
                    event.key(), exception.getClass().getSimpleName());
        }
    }
}
