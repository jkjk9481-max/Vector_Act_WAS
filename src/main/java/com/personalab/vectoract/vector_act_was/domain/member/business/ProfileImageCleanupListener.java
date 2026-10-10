package com.personalab.vectoract.vector_act_was.domain.member.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 저장소 객체 정리를 DB 결과에 맞춰 실행합니다.
 * 실패해도 이미 확정된 요청을 되돌릴 수 없으므로 객체 키와 예외 종류만 기록합니다.
 * 영속 큐와 자동 재시도는 없습니다. 남은 객체는 기록된 키로 별도 정리해야 합니다.
 */
@Component
public class ProfileImageCleanupListener {
    private static final Logger log = LoggerFactory.getLogger(ProfileImageCleanupListener.class);
    private final ProfileImageStorage storage;

    public ProfileImageCleanupListener(ProfileImageStorage storage) {
        this.storage = storage;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteReplaced(ProfileImageObsolete event) {
        delete(event.key(), "OBSOLETE");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void deleteUploadedAfterRollback(ProfileImageUploaded event) {
        delete(event.key(), "ROLLED_BACK");
    }

    private void delete(String key, String reason) {
        try {
            storage.delete(key);
        } catch (RuntimeException exception) {
            log.error("profile_image_cleanup result=FAILED reason={} key={} errorType={}",
                    reason, key, exception.getClass().getSimpleName());
        }
    }
}
