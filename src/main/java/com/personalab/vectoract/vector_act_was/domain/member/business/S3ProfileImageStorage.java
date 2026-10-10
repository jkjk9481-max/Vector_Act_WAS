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

/**
 * 비공개 S3 버킷에 프로필 이미지를 저장합니다. 읽기는 Presigned GET URL로만 제공합니다.
 * 버킷 이름이 설정된 경우({@code storage.s3.bucket})에만 빈으로 등록됩니다(메모리 구현과 정반대 조건).
 *
 * <p>Presigned URL이란: 서버가 AWS 자격 증명으로 "이 객체를 N분 동안 읽을 수 있다"는 서명을 URL에 붙인 것입니다.
 * 버킷을 공개하지 않고도 클라이언트가 이미지를 직접 내려받을 수 있고, 시간이 지나면 URL이 저절로 무효화됩니다.
 */
@Component
@ConditionalOnExpression("!'${storage.s3.bucket:}'.isEmpty()")
public class S3ProfileImageStorage implements ProfileImageStorage {
    // 객체 업로드·삭제용 클라이언트.
    private final S3Client s3;
    // URL 서명 전용 클라이언트(네트워크 호출 없이 로컬에서 서명을 계산합니다).
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
