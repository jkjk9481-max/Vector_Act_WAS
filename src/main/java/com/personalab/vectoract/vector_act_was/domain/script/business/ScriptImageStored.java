package com.personalab.vectoract.vector_act_was.domain.script.business;

/** 원본 이미지를 저장했다는 이벤트입니다. DB 트랜잭션이 롤백되면 이 객체를 삭제합니다. */
public record ScriptImageStored(String key) {}
