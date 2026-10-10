package com.personalab.vectoract.vector_act_was.domain.member.business;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.assertThatCode;

class EmailChangeDeliveryListenerTests {
    @Test
    void missingAdapterDoesNotThrowAfterCommit() {
        var provider = new StaticListableBeanFactory().getBeanProvider(EmailChangeDelivery.class);
        var listener = new EmailChangeDeliveryListener(provider);
        assertThatCode(() -> listener.afterCommit(new EmailChangeRequested(
                "new@example.com", "not-a-real-token", OffsetDateTime.now().plusMinutes(15))))
                .doesNotThrowAnyException();
    }
}
