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
import java.time.ZoneOffset;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** D01 연습 기록 목록 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:coachingsessionlist;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class CoachingSessionListIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired CoachingSessionRepository sessions;
    @Autowired SessionVideoRepository videos;
    @Autowired SessionChunkRepository chunks;
    @Autowired AnalysisRepository analyses;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        analyses.deleteAll();
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

    private CoachingSession session(UUID userId, OffsetDateTime createdAt) {
        return sessions.saveAndFlush(CoachingSession.prepare(userId, "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, createdAt));
    }

    private void complete(CoachingSession session, BigDecimal overallScore) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var analysis = Analysis.queued(session.getUserId(), session.getId(), now);
        analysis.complete(Analysis.Status.COMPLETED, "1.0", "mp-1.lgbm-1", "score-1", 60000, overallScore,
                "요약", "[]", "[]", "[]", now);
        analyses.saveAndFlush(analysis);
    }

    private ResultActions fetch(String query) throws Exception {
        return mvc.perform(get("/api/coaching-sessions" + query).header("Authorization", bearer));
    }

    @Test
    void emptyWhenNoSessions() throws Exception {
        fetch("").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void returnsOwnSessionWithFieldsAndScore() throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var session = session(user.getId(), now);
        complete(session, new BigDecimal("78.46"));

        fetch("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].sessionId").value(session.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].status").value("CREATED"))
                .andExpect(jsonPath("$.data.items[0].videoStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.items[0].analysisStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.items[0].durationMs").value((Object) null))
                .andExpect(jsonPath("$.data.items[0].overallScore").value(78.5));
    }

    @Test
    void sessionWithoutAnalysisHasNullScore() throws Exception {
        session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC));

        fetch("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].overallScore").value((Object) null));
    }

    @Test
    void excludesCanceledAndDeletedAndOthersSessions() throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var canceled = session(user.getId(), now);
        jdbc.update("update coaching_sessions set status = 'CANCELED' where id = ?", canceled.getId());
        var deleted = session(user.getId(), now);
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", deleted.getId());
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        session(other.getId(), now);
        var mine = session(user.getId(), now);

        fetch("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].sessionId").value(mine.getId().toString()));
    }

    @Test
    void defaultSortIsCreatedAtDescending() throws Exception {
        var base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        var older = session(user.getId(), base);
        var newer = session(user.getId(), base.plusHours(1));

        fetch("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sessionId").value(newer.getId().toString()))
                .andExpect(jsonPath("$.data.items[1].sessionId").value(older.getId().toString()));
    }

    @Test
    void ascendingSortReversesOrder() throws Exception {
        var base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        var older = session(user.getId(), base);
        var newer = session(user.getId(), base.plusHours(1));

        fetch("?sort=createdAt,asc").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sessionId").value(older.getId().toString()))
                .andExpect(jsonPath("$.data.items[1].sessionId").value(newer.getId().toString()));
    }

    @Test
    void pagingReturnsSecondPageAndHasNextFalse() throws Exception {
        var base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        for (int i = 0; i < 3; i++) session(user.getId(), base.plusMinutes(i));

        fetch("?page=0&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        fetch("?page=1&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void filtersByFromToDateRangeInKst() throws Exception {
        // 2026-01-05 00:30 KST(=2026-01-04 15:30 UTC)는 from=2026-01-05 조건에서 포함되어야 합니다.
        var inRange = session(user.getId(), OffsetDateTime.parse("2026-01-04T15:30:00Z"));
        var beforeRange = session(user.getId(), OffsetDateTime.parse("2026-01-03T10:00:00Z"));

        fetch("?from=2026-01-05&to=2026-01-05").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].sessionId").value(inRange.getId().toString()));

        fetch("?from=2026-01-03&to=2026-01-03").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].sessionId").value(beforeRange.getId().toString()));
    }

    @Test
    void oneSidedDateSuppliesThirtyDayWindow() throws Exception {
        var inWindow = session(user.getId(), OffsetDateTime.parse("2026-01-20T00:00:00Z"));
        var outsideWindow = session(user.getId(), OffsetDateTime.parse("2025-11-01T00:00:00Z"));

        fetch("?from=2026-01-01").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].sessionId").value(inWindow.getId().toString()));
    }

    @Test
    void invalidPageIsValidationError() throws Exception {
        fetch("?page=-1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void sizeOutOfRangeIsValidationError() throws Exception {
        fetch("?size=0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        fetch("?size=101").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void invalidSortIsValidationError() throws Exception {
        fetch("?sort=status,desc").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void invalidDateFormatIsValidationError() throws Exception {
        fetch("?from=2026/01/01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void fromAfterToIsValidationError() throws Exception {
        fetch("?from=2026-02-01&to=2026-01-01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rangeOverMaxIsValidationError() throws Exception {
        fetch("?from=2026-01-01&to=2027-01-03").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void requiresBearerToken() throws Exception {
        mvc.perform(get("/api/coaching-sessions")).andExpect(status().isUnauthorized());
    }
}
