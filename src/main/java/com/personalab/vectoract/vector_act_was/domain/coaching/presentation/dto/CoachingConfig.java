package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import jakarta.validation.constraints.NotNull;

public record CoachingConfig(
        @NotNull Boolean visualEnabled,
        @NotNull Boolean voiceEnabled,
        @NotNull Boolean analysisOnly,
        @NotNull CoachingSession.Intensity intensity) {}
