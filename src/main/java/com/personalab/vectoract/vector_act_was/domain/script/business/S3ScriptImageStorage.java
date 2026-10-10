package com.personalab.vectoract.vector_act_was.domain.script.business;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * OCR 원본 이미지를 비공개 S3 버킷에 임시 저장합니다. 삭제는 24시간 만료 스케줄러가 담당합니다.
 * 버킷 이름이 설정된 경우({@code storage.s3.bucket})에만 빈으로 등록됩니다.
 *
 * <p>프로필 이미지 저장소와 달리 Presigned URL이 필요 없습니다. OCR 이미지는 클라이언트에 돌려주지 않고
 * 서버가 직접 읽어 엔진에 전달하기 때문입니다.
 */
@Component
@ConditionalOnExpression("!'${storage.s3.bucket:}'.isEmpty()")
public class S3ScriptImageStorage implements ScriptImageStorage {
    private final S3Client s3;
    private final String bucket;

    public S3ScriptImageStorage(S3Client s3, @Value("${storage.s3.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] content) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(content));
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (NoSuchKeyException e) {
            // 인터페이스 계약: 객체가 없으면 예외 대신 null을 돌려줍니다(호출자가 "이미지 유실"로 처리).
            return null;
        }
    }

    // S3 DeleteObject는 없는 객체에도 성공을 반환하므로 재시도해도 안전합니다.
    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
