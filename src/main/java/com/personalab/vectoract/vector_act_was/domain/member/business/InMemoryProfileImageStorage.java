package com.personalab.vectoract.vector_act_was.domain.member.business;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * storage.s3.bucket이 설정되지 않았을 때만 사용하는 임시 저장소입니다. 서버를 재시작하면 이미지가 사라지고,
 * 반환하는 URL도 실제로 내려받을 수 없습니다. 운영에서는 STORAGE_S3_BUCKET을 지정해 S3 구현체를 사용해야 합니다.
 *
 * <p>테스트에서는 {@link #contains}, {@link #keys} 같은 조회 메서드로 "객체가 실제로 저장·삭제됐는지"를 확인합니다.
 */
@Component
@ConditionalOnExpression("'${storage.s3.bucket:}'.isEmpty()")
public class InMemoryProfileImageStorage implements ProfileImageStorage {
    private static final Logger log = LoggerFactory.getLogger(InMemoryProfileImageStorage.class);
    // 여러 요청 스레드가 동시에 접근하므로 스레드 안전한 ConcurrentHashMap을 사용합니다.
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    // 빈 생성 직후 한 번 실행됩니다. 운영에서 실수로 메모리 저장소가 켜져 있으면 로그로 바로 알 수 있게 합니다.
    @PostConstruct
    void warn() {
        log.warn("profile_image_storage type=IN_MEMORY (not for production)");
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        // 호출자가 원본 배열을 나중에 바꿔도 저장된 내용이 변하지 않도록 복사본을 저장합니다.
        objects.put(key, content.clone());
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        // 실제 서명은 하지 않습니다. 만료 시각만 흉내 낸 가짜 URL이며 .invalid 도메인이라 접속할 수 없습니다.
        return URI.create("https://storage.invalid/" + key + "?expires=" + Instant.now().plus(ttl).getEpochSecond());
    }

    // ----- 테스트 확인용 조회 메서드 -----
    public boolean contains(String key) { return objects.containsKey(key); }

    public byte[] get(String key) { return objects.get(key); }

    public Set<String> keys() { return Set.copyOf(objects.keySet()); }
}
