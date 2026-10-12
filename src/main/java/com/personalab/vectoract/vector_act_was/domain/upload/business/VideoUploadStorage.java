package com.personalab.vectoract.vector_act_was.domain.upload.business;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 기존 영상 대용량 업로드(U01~U05)를 위한 S3 Multipart Upload 저장소 계약입니다.
 * 운영에서는 S3 구현체({@link S3VideoUploadStorage})가, 버킷이 설정되지 않은 로컬·테스트에서는
 * {@link InMemoryVideoUploadStorage}가 구현합니다.
 *
 * <p>C04의 presigned PUT과 달리, 2GiB까지의 큰 파일을 여러 Part로 나눠 올리는 S3 Multipart Upload API를 씁니다.
 * 서버는 Part 하나하나를 중계하지 않고, 각 Part에 대한 임시 업로드 URL만 발급합니다(U02).
 */
public interface VideoUploadStorage {
    /** U01: Multipart Upload를 시작하고 S3가 발급한 업로드 ID를 돌려받습니다. */
    String initiateMultipartUpload(String key, String contentType);

    /** U02: 특정 Part 하나를 올릴 임시 URL(Presigned PUT)을 만듭니다. */
    PresignedPart presignUploadPart(String key, String s3UploadId, int partNumber, long sizeBytes,
                                    String sha256Hex, Duration ttl);

    /** U03: 모든 Part를 하나의 객체로 조립합니다. parts는 partNumber 오름차순이어야 합니다. */
    void completeMultipartUpload(String key, String s3UploadId, List<CompletedPart> parts);

    /** U05: 진행 중인 Multipart Upload를 취소하고, 이미 올라간 Part들을 정리합니다(없는 업로드도 성공 처리). */
    void abortMultipartUpload(String key, String s3UploadId);

    /** 발급된 Part 업로드 URL과, 업로드 요청에 그대로 붙여야 하는 서명 헤더입니다. */
    record PresignedPart(URI url, Map<String, String> requiredHeaders) {}

    /** U03에서 클라이언트가 보낸, 완료 조립에 쓸 Part 정보입니다(S3 ETag 포함). */
    record CompletedPart(int partNumber, String etag) {}
}
