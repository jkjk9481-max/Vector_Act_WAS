package com.personalab.vectoract.vector_act_was.domain.upload.business;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * storage.s3.bucket이 설정되지 않았을 때(로컬·테스트)만 쓰는 임시 구현입니다(C04의 InMemoryVideoStorage와 같은 역할).
 * 서버를 재시작하면 업로드 상태가 사라지고, 반환하는 URL은 접속할 수 없는 가짜 주소입니다.
 */
@Component
@ConditionalOnExpression("'${storage.s3.bucket:}'.isEmpty()")
public class InMemoryVideoUploadStorage implements VideoUploadStorage {
    private static final Logger log = LoggerFactory.getLogger(InMemoryVideoUploadStorage.class);
    // key별로 "아직 중단되지 않은 S3 Multipart Upload"를 흉내 냅니다.
    private final Set<String> activeUploads = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void warn() {
        log.warn("video_upload_storage type=IN_MEMORY (not for production)");
    }

    @Override
    public String initiateMultipartUpload(String key, String contentType) {
        activeUploads.add(key);
        return "in-memory-" + UUID.randomUUID();
    }

    @Override
    public PresignedPart presignUploadPart(String key, String s3UploadId, int partNumber, long sizeBytes,
                                           String sha256Hex, Duration ttl) {
        var url = URI.create("https://storage.invalid/" + key + "/parts/" + partNumber
                + "?expires=" + Instant.now().plus(ttl).getEpochSecond());
        var headers = new LinkedHashMap<String, String>();
        headers.put("content-type", "application/octet-stream");
        headers.put("x-amz-checksum-sha256", sha256Hex);
        return new PresignedPart(url, Map.copyOf(headers));
    }

    @Override
    public void completeMultipartUpload(String key, String s3UploadId, List<CompletedPart> parts) {
        activeUploads.remove(key);
    }

    @Override
    public void abortMultipartUpload(String key, String s3UploadId) {
        activeUploads.remove(key);
    }
}
