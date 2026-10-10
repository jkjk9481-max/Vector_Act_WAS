package com.personalab.vectoract.vector_act_was.domain.script.business;

import java.util.UUID;

/** 작업이 커밋되었다는 이벤트입니다. 커밋 후에 OCR 처리를 시작합니다. */
public record ScriptJobSubmitted(UUID jobId) {}
