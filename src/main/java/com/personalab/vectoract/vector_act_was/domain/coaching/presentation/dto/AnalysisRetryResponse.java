package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

/**
 * C11 응답입니다. 재분석이 접수되면 새 시도 번호와 대기(QUEUED) 상태를 돌려줍니다.
 *
 * @param attempt        새 시도 번호(2 또는 3)
 * @param analysisStatus 접수 직후 상태. 항상 QUEUED
 */
public record AnalysisRetryResponse(int attempt, String analysisStatus) {}
