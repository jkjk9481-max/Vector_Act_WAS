package com.personalab.vectoract.vector_act_was.domain.member.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
public class EmailChangeDeliveryListener {
    private static final Logger log = LoggerFactory.getLogger(EmailChangeDeliveryListener.class);
    private final ObjectProvider<EmailChangeDelivery> delivery;

    public EmailChangeDeliveryListener(ObjectProvider<EmailChangeDelivery> delivery) {
        this.delivery = delivery;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(EmailChangeRequested request) {
        try {
            var sender = delivery.getIfAvailable();
            if (sender == null) {
                log.warn("email_change_delivery result=NOT_CONFIGURED");
                return;
            }
            sender.send(request);
        } catch (RuntimeException exception) {
            // 커밋 후 실패는 되돌릴 수 없습니다. 주소·링크가 포함될 수 있는 예외 본문은 기록하지 않습니다.
            log.error("email_change_delivery result=FAILED errorType={}", exception.getClass().getSimpleName());
        }
    }
}
