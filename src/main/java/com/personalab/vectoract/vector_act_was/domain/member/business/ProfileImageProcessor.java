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
 */
@Component
public class ProfileImageProcessor {
    // 디코딩 전에 헤더의 크기만 읽어 지나치게 큰 이미지(압축 폭탄)를 거절합니다.
    private static final long MAX_PIXELS = 25_000_000L;
    private static final float JPEG_QUALITY = 0.9f;

    public record Processed(byte[] content, String contentType, String extension) {}

    public Processed process(byte[] data) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                boolean jpeg = format.equals("jpeg") || format.equals("jpg");
                if (!jpeg && !format.equals("png")) throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
                reader.setInput(input, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR);
                }
                BufferedImage image = reader.read(0);
                return jpeg ? encodeJpeg(image) : encodePng(image);
            } finally {
                reader.dispose();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            // 손상된 파일이나 지원하지 않는 색 공간(CMYK 등)은 JPEG/PNG로 처리할 수 없는 입력입니다.
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
    }

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
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
            stream.flush();
            return new Processed(out.toByteArray(), "image/jpeg", "jpg");
        } finally {
            writer.dispose();
        }
    }
}
