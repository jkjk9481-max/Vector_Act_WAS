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

/** D07 누적 취약점 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dashboardweakness;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class DashboardWeaknessIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired CoachingSessionRepository sessions;
    @Autowired SessionVideoRepository videos;
    @Autowired SessionChunkRepository chunks;
    @Autowired AnalysisRepository analyses;
    @Autowired AnalysisFeedbackRepository feedbacks;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        feedbacks.deleteAll();
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

    private Analysis completedAnalysis(CoachingSession session) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var analysis = Analysis.queued(session.getUserId(), session.getId(), now);
        analysis.complete(Analysis.Status.COMPLETED, "1.0", "mp-1.lgbm-1", "v1", 60000, new BigDecimal("80.00"),
                "요약", "[]", "[]", "[]", now);
        return analyses.saveAndFlush(analysis);
    }

    private void improvement(Analysis analysis, String category, String subcategory, String suggestion) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        feedbacks.saveAndFlush(AnalysisFeedback.of(analysis.getId(), 1000, 2000, category, subcategory,
                "IMPROVEMENT", "WARNING", "메시지", suggestion, now));
    }

    private ResultActions fetch() throws Exception {
        return mvc.perform(get("/api/dashboard/weaknesses").header("Authorization", bearer));
    }

    @Test
    void emptyWhenNoAnalyzedSessionsAtAll() throws Exception {
        fetch().andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.updatedAt").exists());
    }

    @Test
    void sevenCategoriesInFixedOrderWithDefaultsWhenNoFeedback() throws Exception {
        var s = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC));
        completedAnalysis(s);

        fetch().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(7))
                .andExpect(jsonPath("$.data.items[0].category").value("expression"))
                .andExpect(jsonPath("$.data.items[1].category").value("voice"))
                .andExpect(jsonPath("$.data.items[2].category").value("gaze"))
                .andExpect(jsonPath("$.data.items[3].category").value("posture"))
                .andExpect(jsonPath("$.data.items[4].category").value("emotion"))
                .andExpect(jsonPath("$.data.items[5].category").value("scriptDelivery"))
                .andExpect(jsonPath("$.data.items[6].category").value("situationFit"))
                .andExpect(jsonPath("$.data.items[0].eligibleSessionCount").value(1))
                .andExpect(jsonPath("$.data.items[0].affectedSessionCount").value(0))
                .andExpect(jsonPath("$.data.items[0].frequency").value(0.0))
                .andExpect(jsonPath("$.data.items[0].suggestion").value(
                        "여러 연습에서 표정 변화가 적다는 피드백이 반복됐어요. 카메라 연기에서는 얼굴이 "
                        + "감정의 대부분을 전달해요. 연습을 시작할 때마다 감정이 바뀌는 단어를 먼저 표시하고, 그 지점에서 "
                        + "표정을 바꾸는 습관을 들여보세요."))
                .andExpect(jsonPath("$.data.items[3].subItems.length()").value(0)); // posture
    }

    @Test
    void defaultSuggestionForMultiSubcategoryCategoriesUsesFirstSubcategoryPhrase() throws Exception {
        completedAnalysis(session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC)));

        fetch().andExpect(status().isOk())
                // voice의 대표 문구는 세부 항목 중 첫 번째인 speechRate 문구입니다.
                .andExpect(jsonPath("$.data.items[1].category").value("voice"))
                .andExpect(jsonPath("$.data.items[1].suggestion").value(
                        "말 속도가 반복해서 지적됐어요. 속도가 흔들리면 대사의 뜻이 흐려지고 감정도 급하게 "
                        + "보여요. 연습 전에 대본에 쉬는 곳을 표시하는 습관만 들여도 속도가 눈에 띄게 안정돼요."))
                // emotion의 대표 문구는 세부 항목 중 첫 번째인 emotionExpression 문구입니다.
                .andExpect(jsonPath("$.data.items[4].category").value("emotion"))
                .andExpect(jsonPath("$.data.items[4].suggestion").value(
                        "감정 표현이 반복해서 약하게 나타났어요. 감정을 직접 만들려 하기보다, 인물이 "
                        + "상대에게서 원하는 것을 정하고 연기하면 감정이 자연스럽게 따라와요. 연기 전에 목표를 한 문장으로 "
                        + "적는 습관을 들여보세요."));
    }

    @Test
    void affectedSessionCountCountsSessionsWithImprovementFeedbackOnce() throws Exception {
        var withFeedback = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC));
        var a1 = completedAnalysis(withFeedback);
        improvement(a1, "EXPRESSION", "FACIAL_EXPRESSION", "표정을 더 다양하게 해보세요");
        // 같은 세션에 같은 카테고리 피드백이 2개 있어도 세션 단위로는 1번만 집계됩니다.
        improvement(a1, "EXPRESSION", "FACIAL_EXPRESSION", "표정을 더 다양하게 해보세요2");

        var withoutFeedback = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC));
        completedAnalysis(withoutFeedback);

        fetch().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].category").value("expression"))
                .andExpect(jsonPath("$.data.items[0].eligibleSessionCount").value(2))
                .andExpect(jsonPath("$.data.items[0].affectedSessionCount").value(1))
                .andExpect(jsonPath("$.data.items[0].frequency").value(0.5));
    }

    @Test
    void voiceSubItemsAggregatePerSubcategory() throws Exception {
        var s1 = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC));
        var a1 = completedAnalysis(s1);
        improvement(a1, "VOICE", "SPEECH_RATE", "천천히 말해보세요");

        var s2 = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC));
        completedAnalysis(s2);

        fetch().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[1].category").value("voice"))
                .andExpect(jsonPath("$.data.items[1].subItems.length()").value(3))
                .andExpect(jsonPath("$.data.items[1].subItems[0].subcategory").value("speechRate"))
                .andExpect(jsonPath("$.data.items[1].subItems[0].affectedSessionCount").value(1))
                .andExpect(jsonPath("$.data.items[1].subItems[0].eligibleSessionCount").value(2))
                .andExpect(jsonPath("$.data.items[1].subItems[1].subcategory").value("volume"))
                .andExpect(jsonPath("$.data.items[1].subItems[1].affectedSessionCount").value(0))
                .andExpect(jsonPath("$.data.items[1].subItems[2].subcategory").value("intonation"));
    }

    @Test
    void suggestionUsesMostRecentNonNullFeedback() throws Exception {
        var s1 = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC));
        var a1 = completedAnalysis(s1);
        improvement(a1, "EXPRESSION", "FACIAL_EXPRESSION", "오래된 제안");
        Thread.sleep(5);
        var s2 = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC));
        var a2 = completedAnalysis(s2);
        improvement(a2, "EXPRESSION", "FACIAL_EXPRESSION", "최신 제안");

        fetch().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].suggestion").value("최신 제안"));
    }

    @Test
    void excludesCanceledDeletedAndOtherUsersSessions() throws Exception {
        var canceled = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC));
        completedAnalysis(canceled);
        jdbc.update("update coaching_sessions set status = 'CANCELED' where id = ?", canceled.getId());

        var deleted = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC));
        completedAnalysis(deleted);
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", deleted.getId());

        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        completedAnalysis(session(other.getId(), OffsetDateTime.now(ZoneOffset.UTC)));

        var mine = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC));
        completedAnalysis(mine);

        fetch().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].eligibleSessionCount").value(1));
    }

    @Test
    void improvementNoPreviousDataWhenNoSessionsInPriorThirtyDays() throws Exception {
        for (int i = 0; i < 3; i++) {
            var s = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(i));
            completedAnalysis(s);
        }

        fetch().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].improvementStatus").value("NO_PREVIOUS_DATA"))
                .andExpect(jsonPath("$.data.items[0].improvement").value((Object) null));
    }

    @Test
    void improvementInsufficientDataWhenBothWindowsAreEmpty() throws Exception {
        // 최근 30일·직전 30일 모두 0건이면(누적 집계는 더 오래된 세션 1건으로만 존재) NO_PREVIOUS_DATA가 아니라
        // INSUFFICIENT_DATA입니다(D05·D06과 같은 우선순위 — docs/VectorAct_messages_json_확정.zip 참고).
        completedAnalysis(session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(100)));

        fetch().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].improvementStatus").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.data.items[0].improvement").value((Object) null));
    }

    @Test
    void improvementInsufficientDataWhenEitherWindowHasFewerThanThreeSessions() throws Exception {
        for (int i = 0; i < 3; i++) {
            var s = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(35 + i));
            completedAnalysis(s);
        }
        // 최근 30일 쪽은 1건뿐(3개 미만)
        completedAnalysis(session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)));

        fetch().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].improvementStatus").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.data.items[0].improvement").value((Object) null));
    }

    @Test
    void improvementAvailableWhenFrequencyDecreasesInRecentWindow() throws Exception {
        // 직전 30일: 3세션 중 3세션 모두 EXPRESSION 개선 피드백(frequency=1.0)
        for (int i = 0; i < 3; i++) {
            var s = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(35 + i));
            var a = completedAnalysis(s);
            improvement(a, "EXPRESSION", "FACIAL_EXPRESSION", "제안");
        }
        // 최근 30일: 3세션 중 0세션 EXPRESSION 개선 피드백(frequency=0.0) → 개선됨
        for (int i = 0; i < 3; i++) {
            var s = session(user.getId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(i));
            completedAnalysis(s);
        }

        fetch().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].improvementStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].improvement").value(1.0));
    }

    @Test
    void requiresBearerToken() throws Exception {
        mvc.perform(get("/api/dashboard/weaknesses")).andExpect(status().isUnauthorized());
    }
}
