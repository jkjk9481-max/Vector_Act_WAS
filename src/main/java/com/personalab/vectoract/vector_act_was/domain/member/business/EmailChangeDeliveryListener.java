package com.personalab.vectoract.vector_act_was.domain.member.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

/**
 * A13 트랜잭션이 <b>커밋된 뒤에</b> 이메일 변경 메일 발송을 요청하는 리스너입니다.
 *
 * <p>왜 커밋 이후인가: 서비스 안에서 바로 메일을 보내면, 이후 단계에서 롤백될 때 "DB에는 토큰이 없는데
 * 메일은 이미 나간" 상태가 됩니다. {@code AFTER_COMMIT}에 실행하면 토큰이 DB에 확정된 경우에만 발송됩니다.
 */
@Component
public class EmailChangeDeliveryListener {
    private static final Logger log = LoggerFactory.getLogger(EmailChangeDeliveryListener.class);
    // ObjectProvider는 빈이 없어도 주입이 실패하지 않게 합니다. 발송 구현체가 아직 없는 환경을 허용하기 위해서입니다.
    private final ObjectProvider<EmailChangeDelivery> delivery;

    public EmailChangeDeliveryListener(ObjectProvider<EmailChangeDelivery> delivery) {
        this.delivery = delivery;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(EmailChangeRequested request) {
        try {
            var sender = delivery.getIfAvailable();
            if (sender == null) {
                // 구현체가 없으면 토큰은 저장돼 있고 API는 성공으로 응답합니다. 경고만 남깁니다.
                log.warn("email_change_delivery result=NOT_CONFIGURED");
                return;
            }
            sender.send(request);
        } catch (RuntimeException exception) {
            // 커밋 후 실패는 되돌릴 수 없습니다. 주소·링크가 포함될 수 있는 예외 본문은 기록하지 않습니다.
            // 예외가 밖으로 나가도 이미 커밋된 요청의 응답에는 영향이 없으므로 여기서 삼킵니다.
            log.error("email_change_delivery result=FAILED errorType={}", exception.getClass().getSimpleName());
        }
    }
}
