package com.personalab.vectoract.vector_act_was.domain.member.business;

/**
 * 새 객체를 저장했다는 이벤트입니다. DB 트랜잭션이 롤백되면 이 객체를 삭제합니다.
 * ({@link ProfileImageCleanupListener#deleteUploadedAfterRollback}가 AFTER_ROLLBACK에 처리합니다.
 * 커밋되면 이 이벤트는 아무 일도 하지 않습니다.)
 *
 * @param key 방금 저장한 객체의 저장소 key
 */
public record ProfileImageUploaded(String key) {}
