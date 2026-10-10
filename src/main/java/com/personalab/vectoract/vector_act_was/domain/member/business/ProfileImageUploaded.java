package com.personalab.vectoract.vector_act_was.domain.member.business;

/** 새 객체를 저장했다는 이벤트입니다. DB 트랜잭션이 롤백되면 이 객체를 삭제합니다. */
public record ProfileImageUploaded(String key) {}
