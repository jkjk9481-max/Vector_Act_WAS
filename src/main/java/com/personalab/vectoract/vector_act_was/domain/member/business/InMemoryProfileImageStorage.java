package com.personalab.vectoract.vector_act_was.domain.member.business;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실제 S3 연결 전까지 사용하는 임시 저장소입니다. 서버를 재시작하면 이미지가 사라지고,
 * 반환하는 URL도 실제로 내려받을 수 없습니다. 운영 배포 전에 S3 구현체로 교체해야 합니다.
 */
@Component
public class InMemoryProfileImageStorage implements ProfileImageStorage {
    private static final Logger log = LoggerFactory.getLogger(InMemoryProfileImageStorage.class);
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @PostConstruct
    void warn() {
        log.warn("profile_image_storage type=IN_MEMORY (not for production)");
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        objects.put(key, content.clone());
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return URI.create("https://storage.invalid/" + key + "?expires=" + Instant.now().plus(ttl).getEpochSecond());
    }

    public boolean contains(String key) { return objects.containsKey(key); }

    public byte[] get(String key) { return objects.get(key); }

    public Set<String> keys() { return Set.copyOf(objects.keySet()); }
}
