package com.personalab.vectoract.vector_act_was.domain.member.business;

/** 발송 구현체는 신뢰할 수 있는 화면 URL로 링크를 만들고 토큰 원문을 로그에 남기지 않아야 합니다. */
public interface EmailChangeDelivery {
    void send(EmailChangeRequested request);
}
