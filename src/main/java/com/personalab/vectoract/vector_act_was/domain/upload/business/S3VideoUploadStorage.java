package com.personalab.vectoract.vector_act_was.domain.upload.business;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 비공개 S3 버킷에 S3 Multipart Upload API로 대용량 영상을 올립니다.
 * 버킷 이름이 설정된 경우({@code storage.s3.bucket})에만 빈으로 등록됩니다.
 */
@Component
@ConditionalOnExpression("!'${storage.s3.bucket:}'.isEmpty()")
public class S3VideoUploadStorage implements VideoUploadStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3VideoUploadStorage(S3Client s3, S3Presigner presigner, @Value("${storage.s3.bucket}") String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public String initiateMultipartUpload(String key, String contentType) {
        var request = CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).build();
        return s3.createMultipartUpload(request).uploadId();
    }

    @Override
    public PresignedPart presignUploadPart(String key, String s3UploadId, int partNumber, long sizeBytes,
                                           String sha256Hex, Duration ttl) {
        // S3의 체크섬 헤더(x-amz-checksum-sha256)는 hex가 아니라 "원시 바이트의 Base64"를 요구합니다(C04와 동일 규칙).
        String checksumBase64 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex));
        var uploadPart = UploadPartRequest.builder().bucket(bucket).key(key).uploadId(s3UploadId)
                .partNumber(partNumber).contentLength(sizeBytes).checksumSHA256(checksumBase64).build();
        var presigned = presigner.presignUploadPart(UploadPartPresignRequest.builder()
                .signatureDuration(ttl).uploadPartRequest(uploadPart).build());

        var headers = new LinkedHashMap<String, String>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                headers.put(name, String.join(",", values));
            }
        });
        return new PresignedPart(URI.create(presigned.url().toString()), Map.copyOf(headers));
    }

    @Override
    public void completeMultipartUpload(String key, String s3UploadId, List<CompletedPart> parts) {
        var completedParts = parts.stream()
                .map(p -> software.amazon.awssdk.services.s3.model.CompletedPart.builder()
                        .partNumber(p.partNumber()).eTag(p.etag()).build())
                .toList();
        var request = CompleteMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(s3UploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build()).build();
        s3.completeMultipartUpload(request);
    }

    @Override
    public void abortMultipartUpload(String key, String s3UploadId) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(s3UploadId).build());
        } catch (NoSuchUploadException exception) {
            // 이미 완료되었거나 없는 업로드도 취소를 성공으로 처리해 재시도할 수 있게 합니다.
        }
    }
}
