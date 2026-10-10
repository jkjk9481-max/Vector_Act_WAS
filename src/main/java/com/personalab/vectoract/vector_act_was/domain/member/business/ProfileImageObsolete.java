package com.personalab.vectoract.vector_act_was.domain.member.business;

/**
 * 교체·삭제되어 더 이상 참조하지 않는 객체 이벤트입니다. DB 커밋 후에 삭제합니다.
 * ({@link ProfileImageCleanupListener#deleteReplaced}가 AFTER_COMMIT에 처리합니다.)
 *
 * @param key 삭제할 객체의 저장소 key
 */
public record ProfileImageObsolete(String key) {}
