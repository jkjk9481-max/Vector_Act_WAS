package com.personalab.vectoract.vector_act_was.domain.script.business;

/**
 * 원본 이미지를 저장했다는 이벤트입니다. DB 트랜잭션이 롤백되면 이 객체를 삭제합니다.
 * ({@link ScriptJobEventListener#discardAfterRollback}가 AFTER_ROLLBACK에 처리합니다.
 * 커밋되면 이 이벤트는 아무 일도 하지 않습니다.)
 *
 * @param key 방금 저장한 이미지의 저장소 key
 */
public record ScriptImageStored(String key) {}
