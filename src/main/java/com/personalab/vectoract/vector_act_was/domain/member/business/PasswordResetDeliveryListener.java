package com.personalab.vectoract.vector_act_was.domain.member.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

/**
 * 토큰 저장이 확정된 뒤에만 발송을 시도합니다. 발송 실패가 가입 여부를 드러내는 HTTP 오류로
 * 바뀌지 않도록 여기서 처리합니다. 이 이벤트는 메모리 이벤트이며 영속적인 발송 큐가 아닙니다.
 */
@Component
public class PasswordResetDeliveryListener {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetDeliveryListener.class);
    private final ObjectProvider<PasswordResetDelivery> delivery;

    public PasswordResetDeliveryListener(ObjectProvider<PasswordResetDelivery> delivery) {
        this.delivery = delivery;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(PasswordResetRequested request) {
        try {
            var sender = delivery.getIfAvailable();
            if (sender == null) {
                // 개발 단계에서 발송 미연결을 성공으로 기록하지 않습니다. 이메일·토큰은 기록하지 않습니다.
                log.warn("password_reset_delivery result=NOT_CONFIGURED");
                return;
            }
            sender.send(request);
        } catch (RuntimeException exception) {
            // 이미 커밋된 토큰을 되돌릴 수 없습니다. 요청 응답은 다른 이메일과 같은 accepted=true입니다.
            // 예외 본문에는 주소/링크가 있을 수 있어 종류만 기록합니다. 자동 재시도는 아직 없습니다.
            log.error("password_reset_delivery result=FAILED errorType={}", exception.getClass().getSimpleName());
        }
    }
}
