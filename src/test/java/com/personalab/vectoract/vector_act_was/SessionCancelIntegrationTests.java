package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKeyRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** C08 연습 취소 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sessioncancel;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class SessionCancelIntegrationTests {
    private static final String BODY = "{\"reason\":\"USER_REQUEST\"}";
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

    private ResultActions cancel(UUID sessionId, String body) throws Exception {
        return mvc.perform(post("/api/coaching-sessions/" + sessionId + "/cancel")
                .contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", bearer));
    }

    @Test
    void cancelsSessionInEachActiveState() throws Exception {
        for (String state : new String[]{"CREATED", "RECORDING", "FINALIZING"}) {
            var session = created(user.getId());
            jdbc.update("update coaching_sessions set status = ? where id = ?", state, session.getId());

            cancel(session.getId(), BODY).andExpect(status().isAccepted())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.sessionId").value(session.getId().toString()))
                    .andExpect(jsonPath("$.data.status").value("CANCELED"));

            assertThat(sessions.findById(session.getId()).orElseThrow().getStatus())
                    .isEqualTo(CoachingSession.Status.CANCELED);
            sessions.deleteAll();
        }
    }

    @Test
    void repeatedCancelIsAllowed() throws Exception {
        var session = created(user.getId());
        cancel(session.getId(), BODY).andExpect(status().isAccepted());

        cancel(session.getId(), "{\"reason\":\"DEVICE_ERROR\"}").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    @Test
    void cancelFreesActiveSlotForNewSession() throws Exception {
        var session = created(user.getId());
        cancel(session.getId(), BODY).andExpect(status().isAccepted());

        // C01의 활성 세션 1개 규칙: 취소 후에는 새 세션을 준비할 수 있습니다.
        mvc.perform(post("/api/coaching-sessions").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"scriptContent\":\"대본\",\"situation\":\"상황\",\"coaching\":{"
                                + "\"visualEnabled\":true,\"voiceEnabled\":false,\"analysisOnly\":false,"
                                + "\"intensity\":\"NORMAL\"}}"))
                .andExpect(status().isCreated());
    }

    @Test
    void runningAnalysisIsMarkedCanceled() throws Exception {
        var session = created(user.getId());
        jdbc.update("update coaching_sessions set status = 'FINALIZING', analysis_status = 'PROCESSING'");

        cancel(session.getId(), BODY).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.analysisStatus").value("CANCELED"));
    }

    @Test
    void notStartedAnalysisStaysNotStarted() throws Exception {
        var session = created(user.getId());

        cancel(session.getId(), BODY).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.analysisStatus").value("NOT_STARTED"));
    }

    @Test
    void completedOrFailedSessionIsStateConflict() throws Exception {
        var session = created(user.getId());

        for (String state : new String[]{"COMPLETED", "FAILED"}) {
            jdbc.update("update coaching_sessions set status = ?", state);
            cancel(session.getId(), BODY).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        }
    }

    @Test
    void invalidReasonIsValidationError() throws Exception {
        var session = created(user.getId());

        for (String body : new String[]{"{\"reason\":\"BORED\"}", "{}", "not-json"}) {
            cancel(session.getId(), body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        assertThat(sessions.findById(session.getId()).orElseThrow().getStatus())
                .isEqualTo(CoachingSession.Status.CREATED);
    }

    @Test
    void otherUsersOrUnknownOrDeletedSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = created(other.getId());
        var mine = created(user.getId());
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", mine.getId());

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID(), mine.getId()}) {
            cancel(id, BODY).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
        assertThat(sessions.findById(othersSession.getId()).orElseThrow().getStatus())
                .isEqualTo(CoachingSession.Status.CREATED);
    }

    @Test
    void requiresBearerToken() throws Exception {
        var session = created(user.getId());

        mvc.perform(post("/api/coaching-sessions/" + session.getId() + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }
}
