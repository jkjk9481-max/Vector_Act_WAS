package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.Analysis;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
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

/** U07 업로드 분석 상태 조회 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:uploadanalysisstatus;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class UploadAnalysisStatusIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired AnalysisRepository analyses;
    @Autowired CoachingSessionRepository sessions;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        analyses.deleteAll();
        sessions.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    private Analysis queued(UUID userId) {
        return analyses.saveAndFlush(Analysis.queuedForUpload(userId, UUID.randomUUID(), OffsetDateTime.now()));
    }

    private ResultActions fetchStatus(UUID analysisId) throws Exception {
        return mvc.perform(get("/api/upload-analyses/" + analysisId).header("Authorization", bearer));
    }

    @Test
    void returnsQueuedStatus() throws Exception {
        var analysis = queued(user.getId());

        fetchStatus(analysis.getId()).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.analysisId").value(analysis.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.expiresAt").value((Object) null));
    }

    @Test
    void canceledStatusIsReturnedWithExpiry() throws Exception {
        var analysis = queued(user.getId());
        var now = OffsetDateTime.now();
        analysis.cancel(now);
        analyses.saveAndFlush(analysis);

        fetchStatus(analysis.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());
    }

    @Test
    void expiredResultIsGone() throws Exception {
        var analysis = queued(user.getId());
        var now = OffsetDateTime.now();
        analysis.cancel(now);
        analyses.saveAndFlush(analysis);
        jdbc.update("update analyses set expires_at = ?", now.minusSeconds(1));

        fetchStatus(analysis.getId()).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("RESULT_EXPIRED"));
    }

    @Test
    void otherUsersOrUnknownOrLiveCaptureAnalysisIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersAnalysis = queued(other.getId());
        fetchStatus(othersAnalysis.getId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        fetchStatus(UUID.randomUUID()).andExpect(status().isNotFound());

        var session = sessions.saveAndFlush(CoachingSession.prepare(user.getId(), "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, OffsetDateTime.now()));
        var liveAnalysis = analyses.saveAndFlush(Analysis.queued(user.getId(), session.getId(), OffsetDateTime.now()));
        fetchStatus(liveAnalysis.getId()).andExpect(status().isNotFound());
    }

    @Test
    void requiresBearerToken() throws Exception {
        var analysis = queued(user.getId());
        mvc.perform(get("/api/upload-analyses/" + analysis.getId())).andExpect(status().isUnauthorized());
    }
}
