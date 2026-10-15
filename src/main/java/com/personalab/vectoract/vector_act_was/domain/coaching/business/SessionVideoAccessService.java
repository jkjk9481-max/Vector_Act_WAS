package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionVideoRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.SessionVideoUrlResponse;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * D03 영상 재생 URL 발급. 조립이 끝나 재생 가능(READY)한 원본 영상에만 임시 재생 URL을 내려줍니다.
 */
@Service
public class SessionVideoAccessService {
    // API 명세서 D03: 5분 유효 Presigned GET URL.
    private static final Duration URL_TTL = Duration.ofMinutes(5);
    private static final String DEFAULT_CONTENT_TYPE = "video/mp4";

    private final CoachingSessionRepository sessions;
    private final SessionVideoRepository videos;
    private final VideoStorage storage;

    public SessionVideoAccessService(CoachingSessionRepository sessions, SessionVideoRepository videos,
                                     VideoStorage storage) {
        this.sessions = sessions;
        this.videos = videos;
        this.storage = storage;
    }

    /**
     * 오류: 타인 소유·삭제된·없는 세션은 404. 영상이 아직 준비되지 않았으면(READY 전·실패) 409
     * VIDEO_NOT_READY. 30일 경과로 만료·삭제됐으면 410 VIDEO_EXPIRED(분석 결과는 영향 없음).
     */
    @Transactional(readOnly = true)
    public SessionVideoUrlResponse getPlaybackUrl(UUID userId, UUID sessionId) {
        var session = sessions.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        if (session.getVideoStatus() == CoachingSession.VideoStatus.EXPIRED
                || session.getVideoStatus() == CoachingSession.VideoStatus.DELETED) {
            throw new BusinessException(ErrorCode.VIDEO_EXPIRED);
        }
        if (session.getVideoStatus() != CoachingSession.VideoStatus.READY) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_READY);
        }
        var video = videos.findBySessionId(sessionId)
                .filter(v -> v.getObjectKey() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.VIDEO_NOT_READY));

        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var url = storage.presignGet(video.getObjectKey(), URL_TTL);
        String contentType = video.getStoredContentType() != null ? video.getStoredContentType()
                : DEFAULT_CONTENT_TYPE;
        return new SessionVideoUrlResponse(url.toString(), now.plus(URL_TTL), contentType);
    }
}
