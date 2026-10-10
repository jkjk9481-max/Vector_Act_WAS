package com.personalab.vectoract.vector_act_was.domain.member.business;

/**
 * 이메일 변경 확인 메일을 보내는 외부 연동의 계약(포트)입니다.
 *
 * <p>업무 로직은 "메일을 보낸다"는 사실만 알고, SMTP·외부 메일 서비스 같은 실제 방식은 이 인터페이스의
 * 구현체가 정합니다. 이렇게 분리하면 구현체가 아직 없어도 서비스를 개발·테스트할 수 있고(테스트에서는
 * 이 인터페이스를 Mock으로 대체), 나중에 구현체만 추가하면 됩니다.
 *
 * <p>발송 구현체는 신뢰할 수 있는 화면 URL로 링크를 만들고 토큰 원문을 로그에 남기지 않아야 합니다.
 */
public interface EmailChangeDelivery {
    /** 새 이메일 주소로 확인 링크가 담긴 메일을 보냅니다. 호출 시점은 A13 트랜잭션 커밋 이후입니다. */
    void send(EmailChangeRequested request);
}
