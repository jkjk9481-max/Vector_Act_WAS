package com.personalab.vectoract.vector_act_was.domain.script.business;

/** 실제 엔진이 연결되기 전의 기본 구현입니다. 모든 작업을 OCR_ENGINE_UNAVAILABLE로 실패시킵니다. */
public class UnavailableOcrEngine implements OcrEngine {
    @Override
    public String recognize(byte[] image) {
        throw new OcrFailedException(OcrFailedException.ENGINE_UNAVAILABLE);
    }
}
