package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * storage.s3.bucket이 설정되지 않았을 때(로컬·테스트)만 쓰는 임시 구현입니다.
 * 서버를 재시작하면 객체가 사라지고, 반환하는 업로드 URL은 접속할 수 없는 가짜 주소입니다.
 * 운영에서는 STORAGE_S3_BUCKET을 지정해 S3 구현체를 사용해야 합니다.
 *
 * <p>테스트는 {@link #store}로 "클라이언트가 업로드를 마친 상태"를 만들어 C05 검증을 확인합니다.
 */
@Component
@ConditionalOnExpression("'${storage.s3.bucket:}'.isEmpty()")
public class InMemoryVideoStorage implements VideoStorage {
    private static final Logger log = LoggerFactory.getLogger(InMemoryVideoStorage.class);
    // 여러 요청 스레드가 동시에 접근하므로 ConcurrentHashMap을 사용합니다.
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

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

    @Override
    public Optional<StoredObject> stat(String key) {
        byte[] found = objects.get(key);
        if (found == null) return Optional.empty();
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(found);
            return Optional.of(new StoredObject(found.length, HexFormat.of().formatHex(digest)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 제공해야 하는 알고리즘이라 정상적으로는 발생하지 않습니다.
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    // ----- 테스트용: 클라이언트가 업로드를 마친 상태를 흉내 냅니다 -----
    public void store(String key, byte[] content) { objects.put(key, content.clone()); }

    public boolean contains(String key) { return objects.containsKey(key); }
}
