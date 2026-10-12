package com.personalab.vectoract.vector_act_was.domain.upload.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisRepository;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUpload;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUploadPart;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUploadPartRepository;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUploadRepository;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.PartUploadUrlRequest;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.PartUploadUrlResponse;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.UploadCompleteRequest;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.VideoUploadCreateRequest;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.VideoUploadResponse;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKey;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKeyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * U01~U05 기존 영상 대용량 업로드. S3 Multipart Upload 흐름을 감쌉니다.
 * <ul>
 *   <li>U01 {@link #create}: Multipart Upload를 시작하고 업로드 자원을 준비합니다(INITIATED).</li>
 *   <li>U02 {@link #partUploadUrl}: Part 하나를 예약하고 임시 업로드 URL을 발급합니다.</li>
 *   <li>U03 {@link #complete}: 모든 Part를 조립하고 검증합니다(READY/FAILED).</li>
 *   <li>U04 {@link #status}: 현재 상태를 조회합니다.</li>
 *   <li>U05 {@link #cancel}: 분석 접수 전의 업로드를 취소합니다.</li>
 * </ul>
 */
@Service
public class VideoUploadService {
    static final String SCOPE_CREATE = "VIDEO_UPLOAD_CREATE";
    static final List<String> SUPPORTED_CONTENT_TYPES = List.of("video/mp4", "video/webm");
    static final int FILE_NAME_MAX = 255;
    static final Duration PART_URL_TTL = Duration.ofMinutes(10);

    private final UserRepository users;
    private final VideoUploadRepository uploads;
    private final VideoUploadPartRepository parts;
    private final AnalysisRepository analyses;
    private final VideoUploadStorage storage;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final ObjectMapper objectMapper;
    private final long idempotencyTtlHours;

    public VideoUploadService(UserRepository users, VideoUploadRepository uploads, VideoUploadPartRepository parts,
                              AnalysisRepository analyses, VideoUploadStorage storage,
                              IdempotencyKeyRepository idempotencyKeys, ObjectMapper objectMapper,
                              @Value("${idempotency.ttl-hours:24}") long idempotencyTtlHours) {
        this.users = users;
        this.uploads = uploads;
        this.parts = parts;
        this.analyses = analyses;
        this.storage = storage;
        this.idempotencyKeys = idempotencyKeys;
        this.objectMapper = objectMapper;
        this.idempotencyTtlHours = idempotencyTtlHours;
    }

    /**
     * U01 대용량 업로드 시작.
     * 처리 순서: 입력 검증(400/415/413) → 회원 잠금 → 멱등 기록 확인/복원 → S3 Multipart Upload 시작 → 저장.
     */
    @Transactional
    public VideoUploadResponse create(UUID userId, UUID idempotencyKey, VideoUploadCreateRequest request) {
        requireLength(request.fileName(), FILE_NAME_MAX);
        String contentType = canonicalContentType(request.contentType());
        long sizeBytes = request.sizeBytes();
        if (sizeBytes < 1) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (sizeBytes > VideoUpload.MAX_SIZE_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);

        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String hash = hash(request);

        var existing = idempotencyKeys
                .findByUserIdAndIdempotencyKeyAndRequestScope(userId, idempotencyKey, SCOPE_CREATE).orElse(null);
        if (existing != null) {
            if (existing.getExpiresAt().isAfter(now)) {
                if (!existing.getRequestHash().equals(hash)) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                }
                return objectMapper.readValue(existing.getResponseBody(), VideoUploadResponse.class);
            }
            idempotencyKeys.delete(existing);
            idempotencyKeys.flush();
        }

        // 임시 업로드 원본의 object key입니다. ChunkUploadService와 같이 조립 전 조각이 모이는 자리입니다.
        String objectKey = "video-uploads/" + userId + "/" + UUID.randomUUID();
        String s3UploadId = storage.initiateMultipartUpload(objectKey, contentType);

        var upload = uploads.saveAndFlush(VideoUpload.initiate(userId, request.fileName(), contentType, sizeBytes,
                s3UploadId, objectKey, now));
        var response = VideoUploadResponse.of(upload);
        idempotencyKeys.saveAndFlush(IdempotencyKey.create(userId, idempotencyKey, SCOPE_CREATE, hash,
                upload.getId(), 201, objectMapper.writeValueAsString(response), now, now.plusHours(idempotencyTtlHours)));
        return response;
    }

    /**
     * U02 Part 업로드 URL 발급.
     * 처리 순서: 소유·존재(404) → partNumber 범위(400) → 만료·상태(410/409) → Part 크기 일치(400) → 충돌(409) → URL 발급.
     */
    @Transactional
    public PartUploadUrlResponse partUploadUrl(UUID userId, UUID uploadId, int partNumber,
                                               PartUploadUrlRequest request) {
        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        var upload = uploads.findByIdAndUserId(uploadId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (partNumber < 1 || partNumber > upload.getPartCount()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }

        var now = OffsetDateTime.now(ZoneOffset.UTC);
        requireUploadable(upload, now);

        // 마지막 Part만 나머지 크기이고, 그 외에는 고정 Part 크기와 정확히 같아야 합니다.
        long expectedSize = expectedPartSize(upload, partNumber);
        if (request.sizeBytes() != expectedSize) throw new BusinessException(ErrorCode.VALIDATION_ERROR);

        var expiresAt = now.plus(PART_URL_TTL);
        var existing = parts.findByUploadIdAndPartNumber(uploadId, partNumber).orElse(null);
        if (existing == null) {
            parts.saveAndFlush(VideoUploadPart.reserve(uploadId, partNumber, request.sizeBytes(), request.sha256(), now));
        } else if (!existing.sameContent(request.sizeBytes(), request.sha256())) {
            throw new BusinessException(ErrorCode.PART_CONFLICT);
        }
        upload.beginUploading();
        uploads.saveAndFlush(upload);

        var put = storage.presignUploadPart(upload.getObjectKey(), upload.getS3UploadId(), partNumber,
                request.sizeBytes(), request.sha256(), PART_URL_TTL);
        return new PartUploadUrlResponse(partNumber, put.url(), put.requiredHeaders(), expiresAt);
    }

    /**
     * U03 업로드 완료·검증 접수.
     * 처리 순서: 소유·존재(404) → 만료·상태(410/409) → parts 구조 검증(400) → Part별 존재·해시 대조(409) → 조립.
     */
    @Transactional
    public VideoUploadResponse complete(UUID userId, UUID uploadId, UploadCompleteRequest request) {
        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        var upload = uploads.findByIdAndUserId(uploadId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        requireUploadable(upload, now);

        var submitted = request.parts();
        if (submitted.size() != upload.getPartCount()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        for (int i = 0; i < submitted.size(); i++) {
            if (submitted.get(i).partNumber() != i + 1) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }

        var completedParts = new ArrayList<VideoUploadStorage.CompletedPart>();
        var updatedParts = new ArrayList<VideoUploadPart>();
        for (var item : submitted) {
            var part = parts.findByUploadIdAndPartNumber(uploadId, item.partNumber())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PARTS_MISSING));
            if (!part.getSha256().equals(item.sha256())) throw new BusinessException(ErrorCode.CHECKSUM_MISMATCH);
            part.markUploaded(item.etag(), now);
            updatedParts.add(part);
            completedParts.add(new VideoUploadStorage.CompletedPart(item.partNumber(), item.etag()));
        }

        upload.beginVerifying();
        storage.completeMultipartUpload(upload.getObjectKey(), upload.getS3UploadId(), completedParts);
        // 업로드된 영상의 실제 길이(duration_ms)는 미디어 분석(프로브) 단계가 없어 아직 채우지 못합니다(AI 연동 전까지 null).
        upload.ready(null, now);

        parts.saveAll(updatedParts);
        uploads.saveAndFlush(upload);
        return VideoUploadResponse.of(upload);
    }

    /** U04 업로드 상태 조회. 읽기 전용이며 소유자는 어떤 상태든 조회할 수 있습니다. */
    @Transactional(readOnly = true)
    public VideoUploadResponse status(UUID userId, UUID uploadId) {
        var upload = uploads.findByIdAndUserId(uploadId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireUploadNotExpired(upload, OffsetDateTime.now(ZoneOffset.UTC));
        return VideoUploadResponse.of(upload);
    }

    /**
     * U05 업로드 취소. 분석 접수(U06) 전까지만 가능합니다("UPLOAD_IN_USE"는 업로드를 가리키는 분석의 존재로 판단).
     * 이미 취소된 업로드를 다시 취소해도 같은 결과를 돌려줍니다(반복 취소 허용, C08과 같은 패턴).
     */
    @Transactional
    public VideoUploadResponse cancel(UUID userId, UUID uploadId) {
        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        var upload = uploads.findByIdAndUserId(uploadId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        if (analyses.findByUploadId(uploadId).isPresent()) {
            throw new BusinessException(ErrorCode.UPLOAD_IN_USE);
        }
        if (upload.getStatus() != VideoUpload.Status.CANCELED) {
            storage.abortMultipartUpload(upload.getObjectKey(), upload.getS3UploadId());
            upload.cancel(OffsetDateTime.now(ZoneOffset.UTC));
            uploads.saveAndFlush(upload);
        }
        return VideoUploadResponse.of(upload);
    }

    /**
     * U02/U03이 업로드를 받을 수 있는 상태인지 확인합니다. INITIATED/UPLOADING만 허용하며,
     * 만료 시각이 지났으면 410, 그 외 상태(VERIFYING/READY/FAILED/CANCELED)는 409입니다.
     * 운영 DB의 EXPIRED 상태는 정리 스케줄러가 없어 이 메서드가 만료 시각으로 대신 판단합니다(C04와 같은 방식).
     */
    private static void requireUploadable(VideoUpload upload, OffsetDateTime now) {
        switch (upload.getStatus()) {
            case INITIATED, UPLOADING -> requireUploadNotExpired(upload, now);
            default -> throw new BusinessException(ErrorCode.UPLOAD_STATE_CONFLICT);
        }
    }

    private static void requireUploadNotExpired(VideoUpload upload, OffsetDateTime now) {
        boolean stillActive = upload.getStatus() == VideoUpload.Status.INITIATED
                || upload.getStatus() == VideoUpload.Status.UPLOADING
                || upload.getStatus() == VideoUpload.Status.VERIFYING;
        if (stillActive && !upload.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.UPLOAD_EXPIRED);
        }
    }

    private static long expectedPartSize(VideoUpload upload, int partNumber) {
        if (partNumber < upload.getPartCount()) return upload.getPartSizeBytes();
        long remainder = upload.getSizeBytes() - (long) upload.getPartSizeBytes() * (upload.getPartCount() - 1);
        return remainder;
    }

    /** contentType을 지원 목록의 정식 표기로 바꿉니다(C02의 canonicalMimeType과 같은 방식). 지원하지 않으면 415입니다. */
    private static String canonicalContentType(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return SUPPORTED_CONTENT_TYPES.stream().filter(normalized::equals).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    /** 문자열 길이가 1~max인지 코드포인트 기준으로 검사합니다(CoachingSessionService.requireLength와 동일). */
    private static void requireLength(String value, int max) {
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > max) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }

    /** 요청 본문의 SHA-256 해시를 만듭니다. 멱등 키 재사용 시 "같은 요청인지" 비교하는 데 씁니다. */
    private static String hash(VideoUploadCreateRequest r) {
        String canonical = r.fileName().length() + ":" + r.fileName() + "\n" + r.contentType() + "\n" + r.sizeBytes();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
