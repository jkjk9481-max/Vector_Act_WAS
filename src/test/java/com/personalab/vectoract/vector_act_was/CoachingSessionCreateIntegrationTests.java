package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
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

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:coachingsession;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class CoachingSessionCreateIntegrationTests {
    private static final String PATH = "/api/coaching-sessions";
    private static final String BODY = """
            {"scriptContent":"오늘은 너에게 솔직히 말하고 싶어.","situation":"친구에게 이별을 고하는 상황",
             "coaching":{"visualEnabled":true,"voiceEnabled":false,"analysisOnly":false,"intensity":"NORMAL"}}""";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired CoachingSessionRepository sessions;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        idempotencyKeys.deleteAll();
        sessions.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    private ResultActions create(String body, String key) throws Exception {
        return create(body, key, bearer);
    }

    private ResultActions create(String body, String key, String authorization) throws Exception {
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", authorization);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    @Test
    void createsSessionWithStoredConfigAndNotStartedStates() throws Exception {
        create(BODY, key()).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.videoStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.analysisStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.analysisMode").value("REALTIME"))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.startedAt").value((Object) null))
                .andExpect(jsonPath("$.data.endedAt").value((Object) null))
                .andExpect(jsonPath("$.data.durationMs").value((Object) null))
                .andExpect(jsonPath("$.data.videoExpiresAt").value((Object) null))
                .andExpect(jsonPath("$.data.scriptContent").value("오늘은 너에게 솔직히 말하고 싶어."))
                .andExpect(jsonPath("$.data.situation").value("친구에게 이별을 고하는 상황"))
                .andExpect(jsonPath("$.data.coaching.visualEnabled").value(true))
                .andExpect(jsonPath("$.data.coaching.voiceEnabled").value(false))
                .andExpect(jsonPath("$.data.coaching.analysisOnly").value(false))
                .andExpect(jsonPath("$.data.coaching.intensity").value("NORMAL"));

        var saved = sessions.findAll().getFirst();
        assertThat(saved.getUserId()).isEqualTo(user.getId());
        assertThat(saved.getStatus()).isEqualTo(CoachingSession.Status.CREATED);
    }

    @Test
    void sameKeyAndBodyReplaysSameResponseWithoutCreatingAnotherSession() throws Exception {
        String key = key();
        String first = create(BODY, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = create(BODY, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(sessions.count()).isEqualTo(1);
        assertThat(idempotencyKeys.count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsIdempotencyConflict() throws Exception {
        String key = key();
        create(BODY, key).andExpect(status().isCreated());

        create(BODY.replace("NORMAL", "INTENSIVE"), key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    void secondActiveSessionWithNewKeyIsRejected() throws Exception {
        create(BODY, key()).andExpect(status().isCreated());

        create(BODY, key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_SESSION_EXISTS"));
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    void endedSessionDoesNotBlockNewSession() throws Exception {
        create(BODY, key()).andExpect(status().isCreated());
        jdbc.update("update coaching_sessions set status = 'COMPLETED'");

        create(BODY, key()).andExpect(status().isCreated());
        assertThat(sessions.count()).isEqualTo(2);
    }

    @Test
    void sameKeyIsScopedPerUser() throws Exception {
        String key = key();
        create(BODY, key).andExpect(status().isCreated());
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));

        create(BODY, key, "Bearer " + accessTokens.issue(other.getId())).andExpect(status().isCreated());
        assertThat(sessions.count()).isEqualTo(2);
    }

    @Test
    void expiredIdempotencyRecordAllowsKeyToBeReused() throws Exception {
        String key = key();
        create(BODY, key).andExpect(status().isCreated());
        jdbc.update("update coaching_sessions set status = 'CANCELED'");
        jdbc.update("update idempotency_keys set expires_at = ?", OffsetDateTime.now().minusMinutes(1));

        create(BODY.replace("NORMAL", "MINIMAL"), key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.coaching.intensity").value("MINIMAL"));
        assertThat(sessions.count()).isEqualTo(2);
    }

    @Test
    void analysisOnlyWithCoachingEnabledIsConfigConflict() throws Exception {
        create(BODY.replace("\"analysisOnly\":false", "\"analysisOnly\":true"), key())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COACHING_CONFIG_CONFLICT"));
        assertThat(sessions.count()).isZero();
    }

    @Test
    void analysisOnlyWithBothCoachingOffIsAccepted() throws Exception {
        create(BODY.replace("\"analysisOnly\":false", "\"analysisOnly\":true")
                .replace("\"visualEnabled\":true", "\"visualEnabled\":false"), key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.coaching.analysisOnly").value(true));
    }

    @Test
    void invalidInputIsValidationError() throws Exception {
        // 대본 길이 0 / 20001, 상황 길이 0 / 2001, 허용되지 않는 intensity, 필수 필드 누락
        String longScript = "가".repeat(20001);
        String longSituation = "가".repeat(2001);
        String[] bodies = {
                BODY.replace("오늘은 너에게 솔직히 말하고 싶어.", ""),
                BODY.replace("오늘은 너에게 솔직히 말하고 싶어.", longScript),
                BODY.replace("친구에게 이별을 고하는 상황", ""),
                BODY.replace("친구에게 이별을 고하는 상황", longSituation),
                BODY.replace("NORMAL", "EXTREME"),
                BODY.replace("\"voiceEnabled\":false,", ""),
                "{}", "not-json"};
        for (String body : bodies) {
            create(body, key()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        assertThat(sessions.count()).isZero();
    }

    @Test
    void maximumLengthScriptIsAccepted() throws Exception {
        create(BODY.replace("오늘은 너에게 솔직히 말하고 싶어.", "가".repeat(20000)), key())
                .andExpect(status().isCreated());
    }

    @Test
    void missingOrMalformedIdempotencyKeyIsValidationError() throws Exception {
        create(BODY, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        create(BODY, "not-a-uuid").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(sessions.count()).isZero();
    }

    @Test
    void requiresBearerToken() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isUnauthorized());
        assertThat(sessions.count()).isZero();
    }
}
