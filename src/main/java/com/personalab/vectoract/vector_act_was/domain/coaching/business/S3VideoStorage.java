package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 비공개 S3 버킷에 촬영 영상을 올릴 임시 URL(Presigned PUT)을 발급합니다.
 * 버킷 이름이 설정된 경우({@code storage.s3.bucket})에만 빈으로 등록됩니다.
 *
 * <p>서명에 Content-Type, 크기, SHA-256 체크섬을 포함합니다. 클라이언트가 선언과 다른 파일을 올리면
 * S3가 서명 불일치 또는 체크섬 불일치로 거절하므로, 다른 내용의 파일이 이 key에 저장되는 것을 막습니다.
 */
@Component
@ConditionalOnExpression("!'${storage.s3.bucket:}'.isEmpty()")
public class S3VideoStorage implements VideoStorage {
    private final S3Presigner presigner;
    private final String bucket;

    public S3VideoStorage(S3Presigner presigner, @Value("${storage.s3.bucket}") String bucket) {
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public PresignedPut presignPut(String key, String contentType, long sizeBytes, String sha256Hex, Duration ttl) {
        // S3의 체크섬 헤더(x-amz-checksum-sha256)는 hex가 아니라 "원시 바이트의 Base64"를 요구합니다.
        String checksumBase64 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex));
        var put = PutObjectRequest.builder().bucket(bucket).key(key)
                .contentType(contentType).contentLength(sizeBytes).checksumSHA256(checksumBase64).build();
        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl).putObjectRequest(put).build());

        // 서명된 헤더 중 클라이언트가 직접 붙여야 하는 것만 추려 돌려줍니다.
        // host와 content-length는 HTTP 클라이언트(브라우저)가 요청에 맞게 자동으로 설정하므로 제외합니다.
        var headers = new LinkedHashMap<String, String>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                headers.put(name, String.join(",", values));
            }
        });
        return new PresignedPut(URI.create(presigned.url().toString()), Map.copyOf(headers));
    }
}
