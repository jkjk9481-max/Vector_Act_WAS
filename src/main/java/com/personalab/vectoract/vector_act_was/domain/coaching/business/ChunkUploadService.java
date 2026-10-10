package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionChunk;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionChunkRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionVideoRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.ChunkUploadUrlRequest;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.ChunkUploadUrlResponse;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * C04 Chunk 업로드 URL 발급. 청크 하나를 올릴 자리를 예약하고 임시 업로드 URL(Presigned PUT)을 돌려줍니다.
 *
 * <p>전체 업로드 흐름: C04(URL 발급, 이 클래스) → 클라이언트가 URL로 파일 PUT → C05(크기·해시 검증).
 * 같은 청크 번호에 같은 내용으로 다시 요청하면 새 URL만 다시 발급하고(재시도 허용),
 * 다른 내용이면 거절해(CHUNK_CONFLICT) 이미 선언된 청크가 다른 파일로 바뀌는 것을 막습니다.
 */
@Service
public class ChunkUploadService {
    /** 청크 번호 범위(0~120). 10분 ÷ 5초 = 120개, 마지막 번호 포함 121개입니다. */
    static final int MAX_CHUNK_INDEX = 120;
    /** 청크 최대 크기 32MiB (API 명세서 C04). */
    static final long MAX_SIZE_BYTES = 32L * 1024 * 1024;
    /** 업로드 URL 유효 시간 5분 (API 명세서 C04). */
    static final Duration URL_TTL = Duration.ofMinutes(5);

    private final UserRepository users;
    private final CoachingSessionRepository sessions;
    private final SessionVideoRepository videos;
    private final SessionChunkRepository chunks;
    private final VideoStorage storage;

    public ChunkUploadService(UserRepository users, CoachingSessionRepository sessions,
                              SessionVideoRepository videos, SessionChunkRepository chunks, VideoStorage storage) {
        this.users = users;
        this.sessions = sessions;
        this.videos = videos;
        this.chunks = chunks;
        this.storage = storage;
    }

    /**
     * 오류 검사 순서: 입력 값(400, 413) → 소유·존재(404) → 세션 상태(409, 410) → 청크 충돌(409).
     * 요청 자체의 문제를 먼저, 대상의 상태를 나중에 확인합니다.
     */
    @Transactional
    public ChunkUploadUrlResponse issue(UUID userId, UUID sessionId, int chunkIndex, ChunkUploadUrlRequest request) {
        if (chunkIndex < 0 || chunkIndex > MAX_CHUNK_INDEX) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        // 구간이 비어 있거나 뒤집히면 입력 오류입니다(endMs는 startMs보다 커야 합니다).
        if (request.endMs() <= request.startMs()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        // 크기 상한 초과는 "형식 오류"가 아니라 "너무 큼"이라 413으로 구분합니다.
        if (request.sizeBytes() > MAX_SIZE_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);

        // 같은 회원의 동시 요청(같은 청크 번호를 두 번 예약 등)을 직렬화합니다.
        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        // 타인 소유·삭제된·없는 세션은 같은 404입니다.
        var session = sessions.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        requireUploadable(session, now);

        // 촬영 시작(C02)에서 기록한 입력 MIME을 업로드 Content-Type으로 씁니다.
        var video = videos.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_STATE_CONFLICT));

        var expiresAt = now.plus(URL_TTL);
        var existing = chunks.findBySessionIdAndChunkIndex(sessionId, chunkIndex).orElse(null);
        SessionChunk chunk;
        if (existing == null) {
            // staging key: 최종 영상이 아니라 조립 전 임시 조각입니다. UUID를 붙여 key가 항상 고유하게 합니다.
            String key = "session-chunks/" + sessionId + "/" + chunkIndex + "-" + UUID.randomUUID();
            chunk = chunks.saveAndFlush(SessionChunk.reserve(sessionId, chunkIndex, request.startMs(),
                    request.endMs(), request.sizeBytes(), request.sha256(), key, now, expiresAt));
        } else if (existing.sameContent(request.startMs(), request.endMs(), request.sizeBytes(), request.sha256())) {
            // 같은 내용의 재요청(네트워크 오류 후 재시도 등): 기존 key를 그대로 쓰고 새 URL만 발급합니다.
            existing.extendReservation(expiresAt);
            chunk = existing;
        } else {
            // 같은 번호에 다른 내용을 선언하면 이미 예약·검증된 청크가 바뀔 수 있어 거절합니다.
            throw new BusinessException(ErrorCode.CHUNK_CONFLICT);
        }

        var put = storage.presignPut(chunk.getObjectKey(), video.getInputContentType(),
                chunk.getSizeBytes(), chunk.getSha256(), URL_TTL);
        return new ChunkUploadUrlResponse(chunkIndex, put.url(), put.requiredHeaders(), expiresAt);
    }

    /**
     * 청크를 올릴 수 있는 세션 상태인지 확인합니다.
     * <ul>
     *   <li>RECORDING: 촬영 중이므로 가능합니다.</li>
     *   <li>FINALIZING: 촬영 종료(C07) 후 누락 청크를 마감 시각(upload_deadline_at)까지만 올릴 수 있습니다.
     *       마감이 지났으면 410 UPLOAD_EXPIRED입니다.</li>
     *   <li>그 밖의 상태(시작 전, 완료, 실패, 취소): 409 SESSION_STATE_CONFLICT입니다.</li>
     * </ul>
     */
    private static void requireUploadable(CoachingSession session, OffsetDateTime now) {
        switch (session.getStatus()) {
            case RECORDING -> { }
            case FINALIZING -> {
                var deadline = session.getUploadDeadlineAt();
                if (deadline != null && !deadline.isAfter(now)) throw new BusinessException(ErrorCode.UPLOAD_EXPIRED);
            }
            default -> throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
        }
    }
}
