package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import java.time.OffsetDateTime;

/**
 * D03 영상 재생 URL 발급 응답입니다.
 *
 * @param url         5분 유효 Presigned GET URL
 * @param expiresAt   URL 만료 시각
 * @param contentType 저장된 영상의 Content-Type(video/mp4)
 */
public record SessionVideoUrlResponse(String url, OffsetDateTime expiresAt, String contentType) {}
