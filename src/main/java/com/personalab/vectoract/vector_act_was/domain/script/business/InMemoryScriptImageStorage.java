package com.personalab.vectoract.vector_act_was.domain.script.business;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * storage.s3.bucket이 설정되지 않았을 때 쓰는 임시 구현입니다(ScriptOcrConfig가 조건부로 등록).
 * 서버를 재시작하면 이미지가 사라지고 여러 서버 사이에서 공유되지 않습니다.
 * 운영에서는 STORAGE_S3_BUCKET을 지정해 S3 구현체를 사용해야 합니다.
 */
public class InMemoryScriptImageStorage implements ScriptImageStorage {
    // 여러 스레드(요청 스레드, OCR 처리 스레드)가 동시에 접근하므로 ConcurrentHashMap을 사용합니다.
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] content) { objects.put(key, content.clone()); }

    @Override
    public byte[] get(String key) {
        byte[] found = objects.get(key);
        // 배열은 가변 객체라 복사본을 돌려줘야 호출자가 내용을 바꿔도 저장된 값이 영향받지 않습니다.
        return found == null ? null : found.clone();
    }

    @Override
    public void delete(String key) { objects.remove(key); }

    // ----- 테스트 확인용 조회 메서드 -----
    public boolean contains(String key) { return objects.containsKey(key); }

    public Set<String> keys() { return Set.copyOf(objects.keySet()); }
}
