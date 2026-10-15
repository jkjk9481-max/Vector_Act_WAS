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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** D04 연습 기록 삭제 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sessiondeletion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class SessionDeletionIntegrationTests {
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

    private ResultActions delete(UUID sessionId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete("/api/coaching-sessions/" + sessionId)
                .header("Authorization", bearer));
    }

    @Test
    void deletesSessionInEachTerminalState() throws Exception {
        for (String state : new String[]{"COMPLETED", "FAILED", "CANCELED"}) {
            var session = created(user.getId());
            jdbc.update("update coaching_sessions set status = ? where id = ?", state, session.getId());

            delete(session.getId()).andExpect(status().isAccepted())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.deletionStatus").value("PENDING"))
                    .andExpect(jsonPath("$.data.deletedAt").isNotEmpty());

            var reloaded = sessions.findById(session.getId()).orElseThrow();
            assertThat(reloaded.getDeletionStatus()).isEqualTo("PENDING");
            assertThat(reloaded.getDeletedAt()).isNotNull();
            sessions.deleteAll();
        }
    }

    @Test
    void deletedSessionIsExcludedFromFurtherLookup() throws Exception {
        var session = created(user.getId());
        jdbc.update("update coaching_sessions set status = 'COMPLETED' where id = ?", session.getId());

        delete(session.getId()).andExpect(status().isAccepted());

        // D02 상세 조회에서도 같은 404로 제외됩니다(deleted_at IS NULL 조건 공유).
        mvc.perform(get("/api/coaching-sessions/" + session.getId()).header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void activeSessionIsStateConflict() throws Exception {
        for (String state : new String[]{"CREATED", "RECORDING", "FINALIZING"}) {
            var session = created(user.getId());
            jdbc.update("update coaching_sessions set status = ? where id = ?", state, session.getId());

            delete(session.getId()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));

            var reloaded = sessions.findById(session.getId()).orElseThrow();
            assertThat(reloaded.getDeletedAt()).isNull();
            sessions.deleteAll();
        }
    }

    @Test
    void repeatedDeleteOfAlreadyDeletedSessionIs404() throws Exception {
        var session = created(user.getId());
        jdbc.update("update coaching_sessions set status = 'COMPLETED' where id = ?", session.getId());
        delete(session.getId()).andExpect(status().isAccepted());

        delete(session.getId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void otherUsersOrUnknownOrDeletedSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = created(other.getId());
        jdbc.update("update coaching_sessions set status = 'COMPLETED' where id = ?", othersSession.getId());
        var mine = created(user.getId());
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", mine.getId());

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID(), mine.getId()}) {
            delete(id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
        assertThat(sessions.findById(othersSession.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void malformedSessionIdIsValidationError() throws Exception {
        mvc.perform(MockMvcRequestBuilders.delete("/api/coaching-sessions/not-a-uuid")
                        .header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void requiresBearerToken() throws Exception {
        var session = created(user.getId());
        jdbc.update("update coaching_sessions set status = 'COMPLETED' where id = ?", session.getId());

        mvc.perform(MockMvcRequestBuilders.delete("/api/coaching-sessions/" + session.getId()))
                .andExpect(status().isUnauthorized());
    }
}
