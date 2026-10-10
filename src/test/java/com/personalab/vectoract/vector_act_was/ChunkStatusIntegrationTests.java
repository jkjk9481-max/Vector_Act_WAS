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

/** C06 Chunk 현황 조회 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chunkstatus;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class ChunkStatusIntegrationTests {
    private static final String SHA = "a".repeat(64);
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

    private CoachingSession recording(UUID userId) {
        var now = OffsetDateTime.now();
        var session = CoachingSession.prepare(userId, "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, now);
        session.startRecording(now);
        return sessions.saveAndFlush(session);
    }

    private void chunk(CoachingSession session, int index, boolean verified) {
        var chunk = SessionChunk.reserve(session.getId(), index, index * 5000, index * 5000 + 5000, 1000, SHA,
                "session-chunks/" + session.getId() + "/" + index + "-" + UUID.randomUUID(),
                OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(5));
        if (verified) chunk.markVerified(OffsetDateTime.now());
        chunks.saveAndFlush(chunk);
    }

    private ResultActions fetch(UUID sessionId) throws Exception {
        return mvc.perform(get("/api/coaching-sessions/" + sessionId + "/chunks").header("Authorization", bearer));
    }

    @Test
    void emptySessionHasNoItemsAndNoMissing() throws Exception {
        var session = recording(user.getId());

        fetch(session.getId()).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.missingChunkIndexes.length()").value(0));
    }

    @Test
    void listsChunksInOrderAndReportsMissingIndexes() throws Exception {
        var session = recording(user.getId());
        // 번호를 섞어 저장해도 번호 순으로 나와야 합니다. 1번은 선언되지 않았고, 2번은 업로드 대기 상태입니다.
        chunk(session, 3, true);
        chunk(session, 0, true);
        chunk(session, 2, false);

        fetch(session.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].chunkIndex").value(0))
                .andExpect(jsonPath("$.data.items[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.items[0].startMs").value(0))
                .andExpect(jsonPath("$.data.items[0].endMs").value(5000))
                .andExpect(jsonPath("$.data.items[0].sizeBytes").value(1000))
                .andExpect(jsonPath("$.data.items[0].sha256").value(SHA))
                .andExpect(jsonPath("$.data.items[1].chunkIndex").value(2))
                .andExpect(jsonPath("$.data.items[1].status").value("RESERVED"))
                .andExpect(jsonPath("$.data.items[2].chunkIndex").value(3))
                // 0~3 범위에서 검증이 끝나지 않은 번호: 1(미선언), 2(RESERVED)
                .andExpect(jsonPath("$.data.missingChunkIndexes[0]").value(1))
                .andExpect(jsonPath("$.data.missingChunkIndexes[1]").value(2))
                .andExpect(jsonPath("$.data.missingChunkIndexes.length()").value(2));
    }

    @Test
    void allVerifiedHasNoMissing() throws Exception {
        var session = recording(user.getId());
        for (int i = 0; i < 3; i++) chunk(session, i, true);

        fetch(session.getId()).andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.missingChunkIndexes.length()").value(0));
    }

    @Test
    void readableInAnySessionState() throws Exception {
        var session = recording(user.getId());
        chunk(session, 0, true);
        jdbc.update("update coaching_sessions set status = 'COMPLETED'");

        fetch(session.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void doesNotIncludeOtherSessionsChunks() throws Exception {
        var mine = recording(user.getId());
        chunk(mine, 0, true);
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = recording(other.getId());
        chunk(othersSession, 0, true);
        chunk(othersSession, 1, true);

        fetch(mine.getId()).andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void otherUsersOrUnknownOrDeletedSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = recording(other.getId());
        jdbc.update("update coaching_sessions set status = 'CANCELED' where id = ?", othersSession.getId());
        var mine = recording(user.getId());
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", mine.getId());

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID(), mine.getId()}) {
            fetch(id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Test
    void malformedSessionIdIsValidationError() throws Exception {
        mvc.perform(get("/api/coaching-sessions/not-a-uuid/chunks").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void requiresBearerToken() throws Exception {
        var session = recording(user.getId());

        mvc.perform(get("/api/coaching-sessions/" + session.getId() + "/chunks"))
                .andExpect(status().isUnauthorized());
    }
}
