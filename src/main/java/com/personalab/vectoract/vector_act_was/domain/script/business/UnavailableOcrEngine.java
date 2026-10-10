package com.personalab.vectoract.vector_act_was.domain.script.business;

/**
 * 실제 엔진이 연결되기 전의 기본 구현입니다. 모든 작업을 OCR_ENGINE_UNAVAILABLE로 실패시킵니다.
 * 엔진이 없다고 API를 막는 대신, 접수는 받고 결과 조회(S02)에서 실패 코드로 사유를 알립니다.
 */
public class UnavailableOcrEngine implements OcrEngine {
    @Override
    public String recognize(byte[] image) {
        throw new OcrFailedException(OcrFailedException.ENGINE_UNAVAILABLE);
    }
}
