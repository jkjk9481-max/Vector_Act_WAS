package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.business.InMemoryVideoStorage;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * C05 Chunk 업로드 검증 통합 테스트. 클라이언트의 업로드는 {@link InMemoryVideoStorage#store}로 흉내 내고,
 * 서버가 저장소의 실제 객체와 선언 값을 대조해 상태를 확정하는지 확인합니다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chunkverify;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class ChunkVerifyIntegrationTests {
    private static final byte[] CONTENT = "chunk-content".getBytes(StandardCharsets.UTF_8);
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired CoachingSessionRepository sessions;
    @Autowired SessionVideoRepository videos;
    @Autowired SessionChunkRepository chunks;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired InMemoryVideoStorage storage;
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

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private CoachingSession recording(UUID userId) {
        var now = OffsetDateTime.now();
        var session = CoachingSession.prepare(userId, "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, now);
        session.startRecording(now);
        session = sessions.saveAndFlush(session);
        videos.saveAndFlush(SessionVideo.started(session.getId(), "video/webm;codecs=vp8,opus", 1920, 1080,
                new BigDecimal("30.00"), now, now.plusDays(30)));
        return session;
    }

    /** C04를 거친 것과 같은 상태(RESERVED)의 청크를 DB에 만듭니다. 선언 크기·해시는 CONTENT 기준입니다. */
    private SessionChunk reserved(CoachingSession session, int index) throws Exception {
        return chunks.saveAndFlush(SessionChunk.reserve(session.getId(), index, index * 5000, index * 5000 + 5000,
                CONTENT.length, sha256(CONTENT), "session-chunks/" + session.getId() + "/" + index + "-" + UUID.randomUUID(),
                OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(5)));
    }

    private ResultActions complete(UUID sessionId, int chunkIndex) throws Exception {
        return mvc.perform(post("/api/coaching-sessions/" + sessionId + "/chunks/" + chunkIndex + "/complete")
                .header("Authorization", bearer));
    }

    @Test
    void verifiesUploadedChunk() throws Exception {
        var session = recording(user.getId());
        var chunk = reserved(session, 0);
        storage.store(chunk.getObjectKey(), CONTENT);

        complete(session.getId(), 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.chunkIndex").value(0))
                .andExpect(jsonPath("$.data.status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.duplicate").value(false));

        var saved = chunks.findById(chunk.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SessionChunk.Status.VERIFIED);
        assertThat(saved.getVerifiedAt()).isNotNull();
    }

    @Test
    void alreadyVerifiedChunkIsDuplicate() throws Exception {
        var session = recording(user.getId());
        var chunk = reserved(session, 0);
        storage.store(chunk.getObjectKey(), CONTENT);
        complete(session.getId(), 0).andExpect(jsonPath("$.data.duplicate").value(false));

        complete(session.getId(), 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.duplicate").value(true));
    }

    @Test
    void notUploadedObjectIsChunkNotUploaded() throws Exception {
        var session = recording(user.getId());
        var chunk = reserved(session, 0);

        complete(session.getId(), 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHUNK_NOT_UPLOADED"));
        assertThat(chunks.findById(chunk.getId()).orElseThrow().getStatus())
                .isEqualTo(SessionChunk.Status.RESERVED);
    }

    @Test
    void differentContentIsChecksumMismatchAndObjectIsDeleted() throws Exception {
        var session = recording(user.getId());
        var chunk = reserved(session, 0);
        // 크기는 같고 내용만 다른 파일을 올린 경우입니다.
        byte[] tampered = "chunk-CONTENT".getBytes(StandardCharsets.UTF_8);
        storage.store(chunk.getObjectKey(), tampered);

        complete(session.getId(), 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHECKSUM_MISMATCH"));

        assertThat(chunks.findById(chunk.getId()).orElseThrow().getStatus())
                .isEqualTo(SessionChunk.Status.RESERVED);
        // 잘못된 객체는 지워져 다시 올릴 수 있어야 합니다.
        assertThat(storage.contains(chunk.getObjectKey())).isFalse();
    }

    @Test
    void differentSizeIsChecksumMismatch() throws Exception {
        var session = recording(user.getId());
        var chunk = reserved(session, 0);
        storage.store(chunk.getObjectKey(), "short".getBytes(StandardCharsets.UTF_8));

        complete(session.getId(), 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHECKSUM_MISMATCH"));
    }

    @Test
    void unreservedChunkIs404() throws Exception {
        var session = recording(user.getId());

        complete(session.getId(), 7).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void sessionNotUploadableIsStateConflict() throws Exception {
        var session = recording(user.getId());
        var chunk = reserved(session, 0);
        storage.store(chunk.getObjectKey(), CONTENT);

        for (String state : new String[]{"CREATED", "COMPLETED", "FAILED", "CANCELED"}) {
            jdbc.update("update coaching_sessions set status = ?", state);
            complete(session.getId(), 0).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        }
    }

    @Test
    void finalizingAfterDeadlineIsUploadExpired() throws Exception {
        var session = recording(user.getId());
        var chunk = reserved(session, 0);
        storage.store(chunk.getObjectKey(), CONTENT);
        jdbc.update("update coaching_sessions set status = 'FINALIZING', upload_deadline_at = ?",
                OffsetDateTime.now().minusSeconds(1));

        complete(session.getId(), 0).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EXPIRED"));
    }

    @Test
    void finalizingBeforeDeadlineStillVerifies() throws Exception {
        var session = recording(user.getId());
        var chunk = reserved(session, 0);
        storage.store(chunk.getObjectKey(), CONTENT);
        jdbc.update("update coaching_sessions set status = 'FINALIZING', upload_deadline_at = ?",
                OffsetDateTime.now().plusMinutes(5));

        complete(session.getId(), 0).andExpect(status().isOk());
    }

    @Test
    void otherUsersOrUnknownSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = recording(other.getId());
        var chunk = reserved(othersSession, 0);
        storage.store(chunk.getObjectKey(), CONTENT);

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID()}) {
            complete(id, 0).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
        assertThat(chunks.findById(chunk.getId()).orElseThrow().getStatus())
                .isEqualTo(SessionChunk.Status.RESERVED);
    }

    @Test
    void chunkIndexOutOfRangeIsValidationError() throws Exception {
        var session = recording(user.getId());

        complete(session.getId(), 121).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void requiresBearerToken() throws Exception {
        var session = recording(user.getId());

        mvc.perform(post("/api/coaching-sessions/" + session.getId() + "/chunks/0/complete"))
                .andExpect(status().isUnauthorized());
    }
}
