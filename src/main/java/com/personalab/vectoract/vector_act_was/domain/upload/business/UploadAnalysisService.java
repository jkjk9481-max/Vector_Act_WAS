package com.personalab.vectoract.vector_act_was.domain.upload.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.Analysis;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisRepository;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUpload;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUploadRepository;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.UploadAnalysisCreateRequest;
import com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto.UploadAnalysisResponse;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

/**
 * U06(접수)·U07(상태 조회)·U09(취소) 업로드 영상 분석. U08(결과 조회)은 {@link AnalysisResultService}가 맡습니다.
 *
 * <p>이 클래스도 "접수"(분석 행을 QUEUED로 만들거나 상태를 바꾸는 일)까지만 합니다. AI 서버에 실제 분석을
 * 요청하는 단계는 전달 경로가 확정되지 않아 구현하지 않았습니다(AnalysisRetryService와 같은 범위 결정).
 */
@Service
public class UploadAnalysisService {
    static final String SCOPE_SUBMIT = "UPLOAD_ANALYSIS_SUBMIT";
    static final int SCRIPT_MAX = 20000;
    static final int SITUATION_MAX = 2000;

    private final UserRepository users;
    private final VideoUploadRepository uploads;
    private final AnalysisRepository analyses;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final ObjectMapper objectMapper;
    private final long idempotencyTtlHours;

    public UploadAnalysisService(UserRepository users, VideoUploadRepository uploads, AnalysisRepository analyses,
                                 IdempotencyKeyRepository idempotencyKeys, ObjectMapper objectMapper,
                                 @Value("${idempotency.ttl-hours:24}") long idempotencyTtlHours) {
        this.users = users;
        this.uploads = uploads;
        this.analyses = analyses;
        this.idempotencyKeys = idempotencyKeys;
        this.objectMapper = objectMapper;
        this.idempotencyTtlHours = idempotencyTtlHours;
    }

    /**
     * U06 업로드 영상 분석 접수.
     * 처리 순서(C01과 동일한 이유로 멱등 기록을 업로드 상태 검사보다 먼저 확인합니다):
     * 입력 검증(400) → 회원 잠금 → 멱등 기록 확인/복원 → 업로드 소유·존재(404) → READY 확인(409 UPLOAD_NOT_READY)
     * → 기존 분석 존재 확인(409 UPLOAD_IN_USE) → 분석 접수.
     */
    @Transactional
    public UploadAnalysisResponse submit(UUID userId, UUID idempotencyKey, UploadAnalysisCreateRequest request) {
        requireLength(request.scriptContent(), SCRIPT_MAX);
        requireLength(request.situation(), SITUATION_MAX);

        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String hash = hash(request);

        var existing = idempotencyKeys
                .findByUserIdAndIdempotencyKeyAndRequestScope(userId, idempotencyKey, SCOPE_SUBMIT).orElse(null);
        if (existing != null) {
            if (existing.getExpiresAt().isAfter(now)) {
                if (!existing.getRequestHash().equals(hash)) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                }
                return objectMapper.readValue(existing.getResponseBody(), UploadAnalysisResponse.class);
            }
            idempotencyKeys.delete(existing);
            idempotencyKeys.flush();
        }

        var upload = uploads.findByIdAndUserId(request.uploadId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (upload.getStatus() != VideoUpload.Status.READY) throw new BusinessException(ErrorCode.UPLOAD_NOT_READY);
        if (analyses.findByUploadId(upload.getId()).isPresent()) {
            throw new BusinessException(ErrorCode.UPLOAD_IN_USE);
        }

        var analysis = analyses.saveAndFlush(Analysis.queuedForUpload(userId, upload.getId(), now));
        var response = UploadAnalysisResponse.of(analysis);
        idempotencyKeys.saveAndFlush(IdempotencyKey.create(userId, idempotencyKey, SCOPE_SUBMIT, hash,
                analysis.getId(), 202, objectMapper.writeValueAsString(response), now,
                now.plusHours(idempotencyTtlHours)));
        return response;
    }

    /** U07 업로드 분석 상태 조회. 결과 보관 기한(24시간)이 지났으면 410 RESULT_EXPIRED입니다. */
    @Transactional(readOnly = true)
    public UploadAnalysisResponse status(UUID userId, UUID analysisId) {
        var analysis = findOwned(userId, analysisId);
        requireNotExpired(analysis);
        return UploadAnalysisResponse.of(analysis);
    }

    /**
     * U09 업로드 분석 취소. QUEUED/PROCESSING에서만 취소하며, 이미 CANCELED면 같은 결과를 돌려줍니다(반복 취소 허용).
     * 결과 보관 기한이 지났으면 410 RESULT_EXPIRED, 그 외 종료 상태(COMPLETED/PARTIAL/FAILED)는 409입니다.
     */
    @Transactional
    public UploadAnalysisResponse cancel(UUID userId, UUID analysisId) {
        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        var analysis = findOwned(userId, analysisId);
        requireNotExpired(analysis);

        switch (analysis.getStatus()) {
            case QUEUED, PROCESSING -> analyses.saveAndFlush(
                    analysisCanceled(analysis, OffsetDateTime.now(ZoneOffset.UTC)));
            case CANCELED -> { } // 반복 취소: 상태를 바꾸지 않고 현재 상태를 응답합니다.
            default -> throw new BusinessException(ErrorCode.ANALYSIS_STATE_CONFLICT);
        }
        return UploadAnalysisResponse.of(analysis);
    }

    private Analysis analysisCanceled(Analysis analysis, OffsetDateTime now) {
        analysis.cancel(now);
        return analysis;
    }

    private Analysis findOwned(UUID userId, UUID analysisId) {
        return analyses.findByIdAndUserId(analysisId, userId)
                .filter(a -> Analysis.SOURCE_UPLOADED_VIDEO.equals(a.getSource()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static void requireNotExpired(Analysis analysis) {
        var expiresAt = analysis.getExpiresAt();
        if (expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new BusinessException(ErrorCode.RESULT_EXPIRED);
        }
    }

    private static void requireLength(String value, int max) {
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > max) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }

    private static String hash(UploadAnalysisCreateRequest r) {
        String canonical = r.uploadId() + "\n" + r.scriptContent().length() + ":" + r.scriptContent() + "\n"
                + r.situation().length() + ":" + r.situation();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
