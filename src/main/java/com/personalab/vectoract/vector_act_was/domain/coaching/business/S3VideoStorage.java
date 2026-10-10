package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 비공개 S3 버킷에 촬영 영상을 올릴 임시 URL(Presigned PUT)을 발급하고, 올라간 객체를 확인합니다.
 * 버킷 이름이 설정된 경우({@code storage.s3.bucket})에만 빈으로 등록됩니다.
 *
 * <p>서명에 Content-Type, 크기, SHA-256 체크섬을 포함합니다. 클라이언트가 선언과 다른 파일을 올리면
 * S3가 서명 불일치 또는 체크섬 불일치로 거절하므로, 다른 내용의 파일이 이 key에 저장되는 것을 막습니다.
 */
@Component
@ConditionalOnExpression("!'${storage.s3.bucket:}'.isEmpty()")
public class S3VideoStorage implements VideoStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3VideoStorage(S3Client s3, S3Presigner presigner, @Value("${storage.s3.bucket}") String bucket) {
        this.s3 = s3;
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

    /**
     * HEAD로 크기와 저장된 SHA-256 체크섬을 조회합니다. 업로드를 서명된 체크섬과 함께 받았다면
     * S3가 체크섬을 보관하고 있어 객체를 내려받지 않고 확인할 수 있습니다. 체크섬이 없으면 직접 내려받아 계산합니다.
     */
    @Override
    public Optional<StoredObject> stat(String key) {
        try {
            var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key)
                    .checksumMode(ChecksumMode.ENABLED).build());
            String checksum = head.checksumSHA256();
            String hex = checksum != null
                    ? HexFormat.of().formatHex(Base64.getDecoder().decode(checksum))
                    : computeSha256(key);
            return Optional.of(new StoredObject(head.contentLength(), hex));
        } catch (NoSuchKeyException exception) {
            return Optional.empty();
        } catch (S3Exception exception) {
            // HEAD 응답은 본문이 없어 404가 NoSuchKey 대신 일반 S3Exception으로 올 수 있습니다.
            if (exception.statusCode() == 404) return Optional.empty();
            throw exception;
        }
    }

    // S3 DeleteObject는 없는 객체에도 성공을 반환하므로 재시도해도 안전합니다.
    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    // 체크섬이 저장되어 있지 않은 객체를 위한 대체 경로입니다. 스트림으로 읽어 메모리를 적게 씁니다.
    private String computeSha256(String key) {
        try (var in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),
                ResponseTransformer.toInputStream())) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("object checksum calculation failed");
        }
    }
}
