package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * C04 응답입니다. 클라이언트는 {@code putUrl}로 {@code requiredHeaders}를 그대로 붙여 청크 파일을 PUT합니다.
 *
 * @param chunkIndex      청크 번호(요청 경로와 같은 값)
 * @param putUrl          5분 유효 임시 업로드 URL(Presigned PUT)
 * @param requiredHeaders 업로드 요청에 그대로 보내야 하는 헤더. 서명에 포함되어 있어 빠뜨리거나 바꾸면 거절됩니다
 * @param expiresAt       URL 서명 만료 시각
 */
public record ChunkUploadUrlResponse(int chunkIndex, URI putUrl, Map<String, String> requiredHeaders,
                                     OffsetDateTime expiresAt) {
    // 서명된 URL은 그 자체가 업로드 권한이므로 로그에 원문이 남지 않게 가립니다.
    @Override
    public String toString() { return "ChunkUploadUrlResponse[chunkIndex=" + chunkIndex + ", putUrl=REDACTED]"; }
}
