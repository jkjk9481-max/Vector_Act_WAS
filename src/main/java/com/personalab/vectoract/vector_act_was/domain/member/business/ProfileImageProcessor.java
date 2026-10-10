package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Component;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * 업로드된 바이트를 실제로 디코딩해 JPEG/PNG인지 검증하고, 다시 인코딩해 EXIF 등 메타데이터를 제거합니다.
 * 확장자나 Content-Type 헤더는 신뢰하지 않습니다.
 *
 * <p>"다시 인코딩"이 보안·개인정보에 중요한 이유: 사진의 EXIF에는 촬영 위치(GPS), 기기 정보가 들어 있을 수
 * 있습니다. 픽셀만 읽어 새 파일로 쓰면 이런 부가 정보가 자연스럽게 사라지고, 이미지 파일에 몰래 끼워 넣은
 * 다른 데이터(폴리글랏 파일 등)도 제거됩니다.
 */
@Component
public class ProfileImageProcessor {
    // 디코딩 전에 헤더의 크기만 읽어 지나치게 큰 이미지(압축 폭탄)를 거절합니다.
    // 용량은 작아도 가로×세로가 매우 크면 풀었을 때 메모리를 수 GB 쓰기 때문입니다.
    private static final long MAX_PIXELS = 25_000_000L;
    // JPEG 재인코딩 품질(0~1). 용량과 화질의 절충값입니다.
    private static final float JPEG_QUALITY = 0.9f;

    /** 정제가 끝난 이미지. contentType과 extension은 저장소 key·헤더에 그대로 사용합니다. */
    public record Processed(byte[] content, String contentType, String extension) {}

    /**
     * @throws BusinessException UNSUPPORTED_MEDIA_TYPE(JPEG/PNG가 아니거나 손상됨), VALIDATION_ERROR(픽셀 수 초과)
     */
    public Processed process(byte[] data) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            // 파일의 첫 바이트(매직 넘버)로 형식을 판별하는 리더를 찾습니다. 없으면 이미지가 아닙니다.
            var readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                boolean jpeg = format.equals("jpeg") || format.equals("jpg");
                // GIF, BMP, WEBP 등 이미지라도 JPEG/PNG가 아니면 허용하지 않습니다.
                if (!jpeg && !format.equals("png")) throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
                reader.setInput(input, true, true);
                // 픽셀 데이터를 읽기 전에 헤더의 가로·세로만 확인합니다. long 캐스팅은 int 곱셈 오버플로를 막습니다.
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR);
                }
                BufferedImage image = reader.read(0);
                return jpeg ? encodeJpeg(image) : encodePng(image);
            } finally {
                // ImageReader는 네이티브 자원을 잡고 있으므로 반드시 해제합니다.
                reader.dispose();
            }
        } catch (BusinessException exception) {
            // 위에서 의도적으로 던진 업무 오류는 아래의 포괄 처리에 덮이지 않도록 그대로 올립니다.
            throw exception;
        } catch (IOException | RuntimeException exception) {
            // 손상된 파일이나 지원하지 않는 색 공간(CMYK 등)은 JPEG/PNG로 처리할 수 없는 입력입니다.
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
    }

    /** PNG는 무손실이므로 픽셀을 그대로 다시 씁니다(메타데이터 청크는 포함되지 않습니다). */
    private Processed encodePng(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new IOException("png writer unavailable");
        return new Processed(out.toByteArray(), "image/png", "png");
    }

    private Processed encodeJpeg(BufferedImage source) throws IOException {
        // JPEG는 알파 채널을 저장할 수 없어 RGB로 변환합니다.
        var image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var out = new ByteArrayOutputStream(); var stream = ImageIO.createImageOutputStream(out)) {
            var param = writer.getDefaultWriteParam();
            // 압축 품질을 명시적으로 지정하려면 먼저 MODE_EXPLICIT로 바꿔야 합니다.
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(stream);
            // 세 번째 인자(메타데이터)를 null로 넘기면 원본 메타데이터를 복사하지 않습니다.
            writer.write(null, new IIOImage(image, null, null), param);
            stream.flush();
            return new Processed(out.toByteArray(), "image/jpeg", "jpg");
        } finally {
            writer.dispose();
        }
    }
}
