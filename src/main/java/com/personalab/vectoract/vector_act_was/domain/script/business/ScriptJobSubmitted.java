package com.personalab.vectoract.vector_act_was.domain.script.business;

import java.util.UUID;

/**
 * 작업이 커밋되었다는 이벤트입니다. 커밋 후에 OCR 처리를 시작합니다.
 * 처리를 커밋 이후로 미루는 이유: 처리 스레드가 작업 행을 조회할 때 아직 커밋되지 않아 "없는 작업"으로
 * 보이는 경합을 막기 위해서입니다.
 *
 * @param jobId 처리할 OCR 작업 ID
 */
public record ScriptJobSubmitted(UUID jobId) {}
