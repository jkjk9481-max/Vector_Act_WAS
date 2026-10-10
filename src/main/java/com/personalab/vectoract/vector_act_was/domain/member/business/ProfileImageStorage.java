package com.personalab.vectoract.vector_act_was.domain.member.business;

import java.net.URI;
import java.time.Duration;

/**
 * 프로필 이미지 객체 저장소 계약입니다. 운영에서는 S3 구현체가 이 인터페이스를 구현합니다.
 * 저장소 구현은 DB 트랜잭션에 참여하지 않으므로 Service가 커밋·롤백 이후 정리를 담당합니다.
 */
public interface ProfileImageStorage {
    /** key 위치에 객체를 저장합니다. 실패하면 예외를 던져 DB 변경을 막아야 합니다. */
    void put(String key, byte[] content, String contentType);

    /** 객체를 삭제합니다. 이미 없는 객체도 성공으로 처리해야 재시도할 수 있습니다. */
    void delete(String key);

    /** 읽기 전용 임시 URL(Presigned GET)을 만듭니다. */
    URI presignGet(String key, Duration ttl);
}
