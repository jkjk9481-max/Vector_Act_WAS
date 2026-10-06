package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A06 응답 DTO: 클라이언트에 전달할 데이터만 담는 객체입니다.
 * record는 생성자와 userId(), name() 같은 접근 메서드를 자동으로 만들어 줍니다.
 * userId: 회원 식별자. Java에서는 UUID이며 JSON에서는 UUID 형식의 문자열입니다.
 * name: 이름(1~30자), email: 이메일(최대 254자). 이 조건은 기존 회원가입 입력 단계에서 검증합니다.
 * createdAt: 가입 시각. OffsetDateTime은 UTC와의 시차를 포함하며 JSON에서는 날짜·시간 문자열입니다.
 * DB 엔티티, 서비스 결과, JSON 응답 모두 createdAt이라는 이름을 사용합니다.
 */
public record MeResponse(UUID userId, String name, String email, OffsetDateTime createdAt) {
}
