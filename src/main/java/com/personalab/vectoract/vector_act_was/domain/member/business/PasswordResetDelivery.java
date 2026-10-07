package com.personalab.vectoract.vector_act_was.domain.member.business;

/**
 * 실제 이메일 발송 시스템을 연결할 계약입니다. 구현체를 Spring Bean으로 등록해야 합니다.
 * 사용자 입력 Host 헤더 대신 설정된 신뢰할 수 있는 재설정 화면 URL로 링크를 만들어야 합니다.
 * 원문 토큰을 로그에 남기거나 평문 DB에 저장하지 않아야 합니다.
 */
public interface PasswordResetDelivery {
    void send(PasswordResetRequested request);
}
