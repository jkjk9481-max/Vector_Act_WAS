package com.personalab.vectoract.vector_act_was.domain.script.business;

/**
 * OCR 엔진이 작업을 처리하지 못했을 때 S02의 failureCode로 노출할 코드를 담습니다.
 * 이 코드 값 목록은 API 명세서에 없어 구현에서 정한 값입니다(문서 확정 시 교체 대상).
 */
public class OcrFailedException extends RuntimeException {
    /** 연결된 OCR 엔진이 없음. */
    public static final String ENGINE_UNAVAILABLE = "OCR_ENGINE_UNAVAILABLE";
    /** 이미지에서 글자를 하나도 인식하지 못함. */
    public static final String NO_TEXT = "OCR_NO_TEXT";
    /** 추출한 본문이 20000자 상한을 넘음. */
    public static final String RESULT_TOO_LONG = "OCR_RESULT_TOO_LONG";
    /** 그 밖의 처리 실패(원본 이미지 유실, 엔진 내부 오류 등). */
    public static final String FAILED = "OCR_FAILED";

    private final String failureCode;

    public OcrFailedException(String failureCode) {
        // 예외 메시지에는 이미지·본문이 들어가지 않도록 코드만 사용합니다.
        super(failureCode);
        this.failureCode = failureCode;
    }

    public String failureCode() { return failureCode; }
}
