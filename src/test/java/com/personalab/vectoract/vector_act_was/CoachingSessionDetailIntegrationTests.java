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
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** D02 연습 기록 상세 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:coachingsessiondetail;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class CoachingSessionDetailIntegrationTests {
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
        return sessions.saveAndFlush(CoachingSession.prepare(userId, "대본 내용", "상황 설명", true, false, false,
                CoachingSession.Intensity.INTENSIVE, OffsetDateTime.now()));
    }

    private ResultActions fetch(UUID sessionId) throws Exception {
        return mvc.perform(get("/api/coaching-sessions/" + sessionId).header("Authorization", bearer));
    }

    @Test
    void returnsFullDetailOfPreparedSession() throws Exception {
        var session = created(user.getId());

        fetch(session.getId()).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(session.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.videoStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.analysisStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.analysisMode").value("REALTIME"))
                .andExpect(jsonPath("$.data.startedAt").value((Object) null))
                .andExpect(jsonPath("$.data.endedAt").value((Object) null))
                .andExpect(jsonPath("$.data.durationMs").value((Object) null))
                .andExpect(jsonPath("$.data.videoExpiresAt").value((Object) null))
                .andExpect(jsonPath("$.data.scriptContent").value("대본 내용"))
                .andExpect(jsonPath("$.data.situation").value("상황 설명"))
                .andExpect(jsonPath("$.data.coaching.visualEnabled").value(true))
                .andExpect(jsonPath("$.data.coaching.voiceEnabled").value(false))
                .andExpect(jsonPath("$.data.coaching.analysisOnly").value(false))
                .andExpect(jsonPath("$.data.coaching.intensity").value("INTENSIVE"));
    }

    @Test
    void reflectsRecordingStateAndVideoExpiry() throws Exception {
        var session = created(user.getId());
        session.startRecording(OffsetDateTime.now());
        sessions.saveAndFlush(session);

        fetch(session.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECORDING"))
                .andExpect(jsonPath("$.data.videoStatus").value("UPLOADING"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.videoExpiresAt").isNotEmpty());
    }

    @Test
    void otherUsersOrUnknownOrDeletedSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = created(other.getId());
        var mine = created(user.getId());
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", mine.getId());

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID(), mine.getId()}) {
            fetch(id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Test
    void malformedSessionIdIsValidationError() throws Exception {
        mvc.perform(get("/api/coaching-sessions/not-a-uuid").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void requiresBearerToken() throws Exception {
        var session = created(user.getId());

        mvc.perform(get("/api/coaching-sessions/" + session.getId()))
                .andExpect(status().isUnauthorized());
    }
}
