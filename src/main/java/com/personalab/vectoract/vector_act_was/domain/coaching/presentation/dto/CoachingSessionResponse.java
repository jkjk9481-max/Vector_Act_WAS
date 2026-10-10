package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CoachingSessionResponse(
        UUID sessionId, String status, String videoStatus, String analysisStatus, String analysisMode,
        OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime endedAt, Integer durationMs,
        OffsetDateTime videoExpiresAt, String scriptContent, String situation, CoachingConfig coaching) {

    public static CoachingSessionResponse of(CoachingSession s) {
        return new CoachingSessionResponse(s.getId(), s.getStatus().name(), s.getVideoStatus().name(),
                s.getAnalysisStatus().name(), s.getAnalysisMode().name(), s.getCreatedAt(), s.getStartedAt(),
                s.getEndedAt(), s.getDurationMs(), s.getVideoExpiresAt(), s.getScriptContent(), s.getSituation(),
                new CoachingConfig(s.isVisualEnabled(), s.isVoiceEnabled(), s.isAnalysisOnly(),
                        s.getCoachingIntensity()));
    }
}
