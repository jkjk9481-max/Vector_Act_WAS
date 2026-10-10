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
 *
 * <p>동작 요약(둘 중 하나만 실행됩니다):
 * <ul>
 *   <li>커밋됨 → 교체·삭제로 더 이상 쓰이지 않는 <b>이전</b> 객체를 지웁니다.</li>
 *   <li>롤백됨 → 이번 요청에서 새로 올린 객체가 DB에서 참조되지 않으므로 그 객체를 지웁니다.</li>
 * </ul>
 */
@Component
public class ProfileImageCleanupListener {
    private static final Logger log = LoggerFactory.getLogger(ProfileImageCleanupListener.class);
    private final ProfileImageStorage storage;

    public ProfileImageCleanupListener(ProfileImageStorage storage) {
        this.storage = storage;
    }

    /** 트랜잭션이 커밋된 뒤 호출됩니다. 이전 이미지는 이제 DB에서 참조되지 않으므로 안전하게 지울 수 있습니다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteReplaced(ProfileImageObsolete event) {
        delete(event.key(), "OBSOLETE");
    }

    /** 트랜잭션이 롤백된 뒤 호출됩니다. 방금 올린 객체를 가리키는 DB 행이 없으므로 고아 객체가 되기 전에 지웁니다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void deleteUploadedAfterRollback(ProfileImageUploaded event) {
        delete(event.key(), "ROLLED_BACK");
    }

    private void delete(String key, String reason) {
        try {
            storage.delete(key);
        } catch (RuntimeException exception) {
            // 요청은 이미 끝났으므로 예외를 올리지 않고 기록만 합니다. key로 나중에 수동 정리할 수 있습니다.
            log.error("profile_image_cleanup result=FAILED reason={} key={} errorType={}",
                    reason, key, exception.getClass().getSimpleName());
        }
    }
}
