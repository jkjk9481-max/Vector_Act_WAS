package com.personalab.vectoract.vector_act_was.domain.script.business;

/**
 * OCR 원본 이미지의 임시 저장소 계약입니다. 운영에서는 S3 구현체가 이 인터페이스를 구현합니다.
 * 저장소는 DB 트랜잭션에 참여하지 않으므로 Service가 커밋·롤백 이후 정리를 담당합니다.
 *
 * <p>프로필 이미지 저장소({@code ProfileImageStorage})와 별개의 인터페이스로 둔 이유: 용도(영구 보관 vs 24시간
 * 임시 보관)와 필요한 기능(읽기 URL 발급 vs 서버 내부 읽기)이 달라 서로의 변경에 영향받지 않게 하기 위해서입니다.
 */
public interface ScriptImageStorage {
    /** key 위치에 이미지를 저장합니다. 실패하면 예외를 던져 작업 접수를 막아야 합니다. */
    void put(String key, byte[] content);

    /** 저장된 이미지를 읽습니다. 없으면 null을 반환합니다. */
    byte[] get(String key);

    /** 이미지를 삭제합니다. 이미 없는 객체도 성공으로 처리해야 재시도할 수 있습니다. */
    void delete(String key);
}
