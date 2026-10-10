package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 촬영 영상(청크)을 담는 객체 저장소 계약입니다. 운영에서는 S3 구현체({@link S3VideoStorage})가 구현합니다.
 *
 * <p>영상은 용량이 커서 서버를 거치지 않고, 클라이언트가 서버가 발급한 임시 URL로 저장소에 직접 업로드합니다
 * (presigned PUT). 서버는 "누가, 어떤 key에, 어떤 크기·해시의 파일을, 언제까지 올릴 수 있는지"만 서명으로 제한합니다.
 */
public interface VideoStorage {
    /**
     * 업로드용 임시 URL(Presigned PUT)을 만듭니다.
     *
     * @param key         저장할 객체 key
     * @param contentType 업로드 파일의 Content-Type(촬영 시작 때 알려 준 입력 MIME)
     * @param sizeBytes   선언한 파일 크기. 서명에 포함해 다른 크기의 업로드를 막습니다
     * @param sha256Hex   선언한 SHA-256(소문자 hex). 저장소가 업로드 내용과 대조하도록 서명에 포함합니다
     * @param ttl         URL 유효 시간
     */
    PresignedPut presignPut(String key, String contentType, long sizeBytes, String sha256Hex, Duration ttl);

    /**
     * 발급된 업로드 URL과, 업로드 요청에 <b>그대로</b> 붙여야 하는 헤더 목록입니다.
     * 서명에 포함된 헤더를 빠뜨리거나 바꾸면 저장소가 서명 불일치로 거절합니다.
     */
    record PresignedPut(URI url, Map<String, String> requiredHeaders) {}
}
