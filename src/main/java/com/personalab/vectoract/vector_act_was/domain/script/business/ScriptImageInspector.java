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
 *
 * <p>프로필 이미지 처리기({@code ProfileImageProcessor})와의 차이: 프로필은 메타데이터 제거를 위해 다시
 * 인코딩하지만, OCR은 글자 인식 품질을 위해 원본 픽셀이 그대로 필요합니다. 그래서 이 클래스는 "검사만" 합니다.
 */
@Component
public class ScriptImageInspector {
    // 디코딩 단계에서 메모리를 과도하게 쓰는 이미지를 헤더 단계에서 거절합니다.
    private static final long MAX_PIXELS = 25_000_000L;

    /**
     * 이미지가 JPEG/PNG이고 크기가 허용 범위인지 검사합니다. 통과하면 아무것도 반환하지 않습니다.
     *
     * @throws BusinessException UNSUPPORTED_MEDIA_TYPE(형식 불일치·손상), VALIDATION_ERROR(픽셀 수 초과)
     */
    public void inspect(byte[] data) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            // 파일 앞부분(매직 넘버)으로 형식을 판별하는 리더를 찾습니다. 없으면 이미지가 아닙니다.
            var readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("jpg") && !format.equals("png")) {
                    throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
                }
                reader.setInput(input, true, true);
                // 픽셀을 디코딩하기 전에 헤더의 가로·세로만 읽습니다. long 캐스팅은 int 곱셈 오버플로를 막습니다.
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR);
                }
            } finally {
                // ImageReader는 네이티브 자원을 잡고 있으므로 반드시 해제합니다.
                reader.dispose();
            }
        } catch (BusinessException exception) {
            // 의도적으로 던진 업무 오류가 아래의 포괄 처리에 덮이지 않도록 그대로 올립니다.
            throw exception;
        } catch (IOException | RuntimeException exception) {
            // 헤더가 손상되어 읽을 수 없는 입력은 지원하지 않는 형식으로 취급합니다.
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
    }
}
