package com.personalab.vectoract.vector_act_was.domain.member.business;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.assertThatCode;

class PasswordResetDeliveryListenerTests {
    @Test
    void missingDeliveryAdapterDoesNotChangeAcceptedResponseIntoAccountSpecificError() {
        // 실제 발송 Bean이 없는 현재 개발 상태도 예외로 가입 여부를 노출하지 않아야 합니다.
        var provider = new StaticListableBeanFactory().getBeanProvider(PasswordResetDelivery.class);
        var listener = new PasswordResetDeliveryListener(provider);
        assertThatCode(() -> listener.afterCommit(new PasswordResetRequested(
                "test@example.com", "not-a-real-token", OffsetDateTime.now().plusMinutes(15))))
                .doesNotThrowAnyException();
    }
}
