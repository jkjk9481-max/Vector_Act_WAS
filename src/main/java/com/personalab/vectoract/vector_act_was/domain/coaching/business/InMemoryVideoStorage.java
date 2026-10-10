package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * storage.s3.bucket이 설정되지 않았을 때(로컬·테스트)만 쓰는 임시 구현입니다.
 * 실제로 파일을 받지 않으며, 반환하는 URL은 접속할 수 없는 가짜 주소입니다.
 * 운영에서는 STORAGE_S3_BUCKET을 지정해 S3 구현체를 사용해야 합니다.
 */
@Component
@ConditionalOnExpression("'${storage.s3.bucket:}'.isEmpty()")
public class InMemoryVideoStorage implements VideoStorage {
    private static final Logger log = LoggerFactory.getLogger(InMemoryVideoStorage.class);

    // 빈 생성 직후 한 번 실행됩니다. 운영에서 실수로 켜져 있으면 로그로 바로 알 수 있게 합니다.
    @PostConstruct
    void warn() {
        log.warn("video_storage type=IN_MEMORY (not for production)");
    }

    @Override
    public PresignedPut presignPut(String key, String contentType, long sizeBytes, String sha256Hex, Duration ttl) {
        // 실제 서명 없이 만료 시각만 흉내 낸 가짜 URL입니다(.invalid 도메인이라 접속할 수 없습니다).
        var url = URI.create("https://storage.invalid/" + key + "?expires=" + Instant.now().plus(ttl).getEpochSecond());
        // 실제 구현(S3)과 같은 모양의 헤더를 돌려줘 호출 측 로직을 같은 방식으로 검증할 수 있게 합니다.
        var headers = new LinkedHashMap<String, String>();
        headers.put("content-type", contentType);
        headers.put("x-amz-checksum-sha256", sha256Hex);
        return new PresignedPut(url, Map.copyOf(headers));
    }
}
