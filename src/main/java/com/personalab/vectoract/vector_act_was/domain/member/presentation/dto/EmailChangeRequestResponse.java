package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

/**
 * A13 응답 본문입니다. 메일 발송 결과와 무관하게 요청이 접수되면 {@code accepted=true}입니다.
 * (발송은 커밋 이후 비동기 단계라 이 시점에 성공 여부를 알 수 없고, 토큰·주소 노출을 피하기 위해 값을 더 담지 않습니다.)
 */
public record EmailChangeRequestResponse(boolean accepted) {}
