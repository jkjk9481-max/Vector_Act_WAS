package com.personalab.vectoract.vector_act_was.domain.member.business;

import java.time.OffsetDateTime;

/**
 * A13이 토큰 발급을 끝냈음을 알리는 도메인 이벤트입니다. 메일 발송 단계로 값을 전달합니다.
 *
 * @param newEmail  확인 메일을 받을 새 이메일 주소(정규화된 값)
 * @param rawToken  메일 링크에 담을 토큰 <b>원문</b>. 이 값은 DB에 저장되지 않으므로 이 객체가 유일한 전달 경로입니다
 * @param expiresAt 토큰 만료 시각(메일 본문 안내용)
 */
public record EmailChangeRequested(String newEmail, String rawToken, OffsetDateTime expiresAt) {
    // record의 기본 toString은 모든 필드를 출력합니다. 로그에 토큰 원문이 찍히지 않도록 가립니다.
    @Override
    public String toString() { return "EmailChangeRequested[credentials=REDACTED]"; }
}
