package com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;

/** U02 응답. putUrl에 Part 바이너리를 PUT한 뒤 받는 ETag를 U03에 전달해야 합니다. */
public record PartUploadUrlResponse(
        int partNumber, URI putUrl, Map<String, String> requiredHeaders, OffsetDateTime expiresAt) {}
