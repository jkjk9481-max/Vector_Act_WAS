package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

/** A14 응답 본문입니다. 성공하면 항상 {@code accepted=true}이며, 실패는 오류 응답으로 내려갑니다. */
public record EmailChangeCompletionResponse(boolean accepted) {}
