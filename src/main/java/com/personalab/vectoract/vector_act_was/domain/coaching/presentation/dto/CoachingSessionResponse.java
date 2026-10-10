package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 연습 세션 응답입니다. C01·C02가 같은 모양을 쓰며, 이후 세션 조회 API도 이 DTO를 재사용할 수 있습니다.
 * null 가능 필드(startedAt 등)도 응답에서 생략하지 않고 {@code null}로 내려갑니다(명세의 "필수, null 허용").
 *
 * <p>이 record는 멱등 기록(idempotency_keys.response_body)에 JSON으로 저장됐다가 재시도 시 다시 읽히므로,
 * JSON 변환(직렬화·역직렬화)이 가능한 단순한 값만 필드로 둡니다.
 */
public record CoachingSessionResponse(
        UUID sessionId, String status, String videoStatus, String analysisStatus, String analysisMode,
        OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime endedAt, Integer durationMs,
        OffsetDateTime videoExpiresAt, String scriptContent, String situation, CoachingConfig coaching) {

    /** 엔티티를 응답으로 변환합니다. enum은 이름 문자열로 바꿔 내려줍니다. */
    public static CoachingSessionResponse of(CoachingSession s) {
        return new CoachingSessionResponse(s.getId(), s.getStatus().name(), s.getVideoStatus().name(),
                s.getAnalysisStatus().name(), s.getAnalysisMode().name(), s.getCreatedAt(), s.getStartedAt(),
                s.getEndedAt(), s.getDurationMs(), s.getVideoExpiresAt(), s.getScriptContent(), s.getSituation(),
                new CoachingConfig(s.isVisualEnabled(), s.isVoiceEnabled(), s.isAnalysisOnly(),
                        s.getCoachingIntensity()));
    }
}
