package com.personalab.vectoract.vector_act_was.domain.script.business;

/** OCR 엔진이 작업을 처리하지 못했을 때 S02의 failureCode로 노출할 코드를 담습니다. */
public class OcrFailedException extends RuntimeException {
    public static final String ENGINE_UNAVAILABLE = "OCR_ENGINE_UNAVAILABLE";
    public static final String NO_TEXT = "OCR_NO_TEXT";
    public static final String RESULT_TOO_LONG = "OCR_RESULT_TOO_LONG";
    public static final String FAILED = "OCR_FAILED";

    private final String failureCode;

    public OcrFailedException(String failureCode) {
        // 예외 메시지에는 이미지·본문이 들어가지 않도록 코드만 사용합니다.
        super(failureCode);
        this.failureCode = failureCode;
    }

    public String failureCode() { return failureCode; }
}
