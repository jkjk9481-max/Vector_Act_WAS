package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKeyRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** D03 영상 재생 URL 발급 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sessionvideourl;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class SessionVideoUrlIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired CoachingSessionRepository sessions;
    @Autowired SessionVideoRepository videos;
    @Autowired SessionChunkRepository chunks;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        chunks.deleteAll();
        videos.deleteAll();
        idempotencyKeys.deleteAll();
        sessions.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    private CoachingSession created(UUID userId) {
        return sessions.saveAndFlush(CoachingSession.prepare(userId, "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, OffsetDateTime.now()));
    }

    /** 조립이 끝나 재생 가능(READY)한 세션을 만듭니다. */
    private String ready(CoachingSession session) {
        var now = OffsetDateTime.now();
        var video = videos.saveAndFlush(SessionVideo.started(session.getId(),
                "video/webm;codecs=vp8,opus", 1280, 720, new BigDecimal("30.00"), now, now.plusDays(30)));
        String objectKey = "session-videos/" + session.getId() + "/" + UUID.randomUUID();
        jdbc.update("update session_videos set object_key = ?, stored_content_type = 'video/mp4' where id = ?",
                objectKey, video.getId());
        jdbc.update("update coaching_sessions set video_status = 'READY' where id = ?", session.getId());
        return objectKey;
    }

    private ResultActions fetch(UUID sessionId) throws Exception {
        return mvc.perform(get("/api/coaching-sessions/" + sessionId + "/video").header("Authorization", bearer));
    }

    @Test
    void returnsPresignedUrlForReadyVideo() throws Exception {
        var session = created(user.getId());
        String objectKey = ready(session);

        fetch(session.getId()).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.url", org.hamcrest.Matchers.containsString(objectKey)))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.contentType").value("video/mp4"));
    }

    @Test
    void notStartedVideoIsVideoNotReady() throws Exception {
        var session = created(user.getId());

        fetch(session.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VIDEO_NOT_READY"));
    }

    @Test
    void assemblingVideoIsVideoNotReady() throws Exception {
        var session = created(user.getId());
        session.startRecording(OffsetDateTime.now());
        sessions.saveAndFlush(session);
        jdbc.update("update coaching_sessions set video_status = 'ASSEMBLING' where id = ?", session.getId());

        fetch(session.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VIDEO_NOT_READY"));
    }

    @Test
    void expiredVideoIsGone() throws Exception {
        var session = created(user.getId());
        ready(session);
        jdbc.update("update coaching_sessions set video_status = 'EXPIRED' where id = ?", session.getId());

        fetch(session.getId()).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("VIDEO_EXPIRED"));
    }

    @Test
    void deletedVideoIsGone() throws Exception {
        var session = created(user.getId());
        ready(session);
        jdbc.update("update coaching_sessions set video_status = 'DELETED' where id = ?", session.getId());

        fetch(session.getId()).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("VIDEO_EXPIRED"));
    }

    @Test
    void otherUsersOrUnknownOrDeletedSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = created(other.getId());
        ready(othersSession);
        var mine = created(user.getId());
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", mine.getId());

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID(), mine.getId()}) {
            fetch(id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Test
    void malformedSessionIdIsValidationError() throws Exception {
        mvc.perform(get("/api/coaching-sessions/not-a-uuid/video").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void requiresBearerToken() throws Exception {
        var session = created(user.getId());
        ready(session);

        mvc.perform(get("/api/coaching-sessions/" + session.getId() + "/video"))
                .andExpect(status().isUnauthorized());
    }
}
