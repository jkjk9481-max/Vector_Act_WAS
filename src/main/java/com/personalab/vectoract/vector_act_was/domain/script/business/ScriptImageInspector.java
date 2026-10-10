package com.personalab.vectoract.vector_act_was.domain.script.business;

import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * 확장자나 Content-Type 헤더가 아닌 실제 바이트로 JPEG/PNG 여부를 판별합니다.
 * OCR에는 원본이 필요하므로 이미지를 다시 인코딩하지 않고 헤더만 읽어 검증합니다.
 */
@Component
public class ScriptImageInspector {
    // 디코딩 단계에서 메모리를 과도하게 쓰는 이미지를 헤더 단계에서 거절합니다.
    private static final long MAX_PIXELS = 25_000_000L;

    public void inspect(byte[] data) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("jpg") && !format.equals("png")) {
                    throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
                }
                reader.setInput(input, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR);
                }
            } finally {
                reader.dispose();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
    }
}
