package com.personalab.vectoract.vector_act_was.domain.script.business;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실제 저장소 연결 전까지 쓰는 임시 구현입니다. 서버를 재시작하면 이미지가 사라지고
 * 여러 서버 사이에서 공유되지 않습니다. 운영 배포 전에 S3 구현체로 교체해야 합니다.
 */
public class InMemoryScriptImageStorage implements ScriptImageStorage {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] content) { objects.put(key, content.clone()); }

    @Override
    public byte[] get(String key) {
        byte[] found = objects.get(key);
        return found == null ? null : found.clone();
    }

    @Override
    public void delete(String key) { objects.remove(key); }

    public boolean contains(String key) { return objects.containsKey(key); }

    public Set<String> keys() { return Set.copyOf(objects.keySet()); }
}
