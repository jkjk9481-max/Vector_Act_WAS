package com.personalab.vectoract.vector_act_was.domain.member.business;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import java.net.URI;
import java.time.Duration;

/** 비공개 S3 버킷에 프로필 이미지를 저장합니다. 읽기는 Presigned GET URL로만 제공합니다. */
@Component
@ConditionalOnExpression("!'${storage.s3.bucket:}'.isEmpty()")
public class S3ProfileImageStorage implements ProfileImageStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ProfileImageStorage(S3Client s3, S3Presigner presigner, @Value("${storage.s3.bucket}") String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(content));
    }

    // S3 DeleteObject는 없는 객체에도 성공을 반환하므로 재시도해도 안전합니다.
    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        var request = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build();
        return URI.create(presigner.presignGetObject(request).url().toString());
    }
}
