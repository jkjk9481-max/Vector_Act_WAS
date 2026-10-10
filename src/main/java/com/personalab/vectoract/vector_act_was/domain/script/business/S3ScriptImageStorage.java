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

/** OCR 원본 이미지를 비공개 S3 버킷에 임시 저장합니다. 삭제는 24시간 만료 스케줄러가 담당합니다. */
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
            return null;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
