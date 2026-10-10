package com.personalab.vectoract.vector_act_was.domain.script.business;

/**
 * 이미지에서 텍스트를 추출하는 엔진 계약입니다. 실제 엔진(WAS 내장, AI 서버 등)은 문서에 확정되지 않아
 * 구현체를 교체할 수 있게 분리했습니다. 구현체는 호출 스레드에서 동기적으로 결과를 반환해야 합니다.
 *
 * <p>구현체를 추가하려면 이 인터페이스를 구현한 빈을 등록하면 됩니다. {@code ScriptOcrConfig}가
 * "OcrEngine 빈이 없을 때만" 기본 구현({@link UnavailableOcrEngine})을 등록하므로 자동으로 교체됩니다.
 */
public interface OcrEngine {
    /**
     * @param image 원본 JPEG/PNG 바이트
     * @return 추출한 텍스트. 인식된 글자가 없으면 빈 문자열 또는 null
     * @throws OcrFailedException 엔진이 의도적으로 실패 코드를 알려야 할 때
     */
    String recognize(byte[] image);
}
