package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
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

/** U03 업로드 완료·검증 접수 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:uploadcomplete;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class UploadCompleteIntegrationTests {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired VideoUploadRepository uploads;
    @Autowired VideoUploadPartRepository parts;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        parts.deleteAll();
        uploads.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    /** sizeBytes 20,000,000(partCount=2)의 UPLOADING 업로드와, 두 Part 모두 예약된 상태를 만듭니다. */
    private VideoUpload uploadWithReservedParts(UUID userId) {
        var now = OffsetDateTime.now();
        var upload = uploads.saveAndFlush(VideoUpload.initiate(userId, "practice.mp4", "video/mp4", 20_000_000L,
                "s3-upload-1", "video-uploads/" + userId + "/" + UUID.randomUUID(), now));
        upload.beginUploading();
        uploads.saveAndFlush(upload);
        parts.saveAndFlush(VideoUploadPart.reserve(upload.getId(), 1, 16777216L, SHA_A, now));
        parts.saveAndFlush(VideoUploadPart.reserve(upload.getId(), 2, 20_000_000L - 16777216L, SHA_B, now));
        return upload;
    }

    private static String completeBody(String etag1, String sha1, String etag2, String sha2) {
        return "{\"parts\":[{\"partNumber\":1,\"etag\":\"" + etag1 + "\",\"sha256\":\"" + sha1 + "\"},"
                + "{\"partNumber\":2,\"etag\":\"" + etag2 + "\",\"sha256\":\"" + sha2 + "\"}]}";
    }

    private ResultActions complete(UUID uploadId, String body) throws Exception {
        return mvc.perform(post("/api/video-uploads/" + uploadId + "/complete")
                .contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", bearer));
    }

    @Test
    void completesAllPartsAndBecomesReady() throws Exception {
        var upload = uploadWithReservedParts(user.getId());

        complete(upload.getId(), completeBody("etag-1", SHA_A, "etag-2", SHA_B))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.uploadId").value(upload.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.partCount").value(2));

        var saved = uploads.findById(upload.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(VideoUpload.Status.READY);
        assertThat(saved.getCompletedAt()).isNotNull();
        for (var part : parts.findByUploadIdOrderByPartNumberAsc(upload.getId())) {
            assertThat(part.getStatus()).isEqualTo(VideoUploadPart.Status.UPLOADED);
            assertThat(part.getEtag()).isNotBlank();
        }
    }

    @Test
    void missingReservedPartIsPartsMissing() throws Exception {
        var now = OffsetDateTime.now();
        var upload = uploads.saveAndFlush(VideoUpload.initiate(user.getId(), "practice.mp4", "video/mp4",
                20_000_000L, "s3-upload-1", "video-uploads/" + user.getId() + "/" + UUID.randomUUID(), now));
        upload.beginUploading();
        uploads.saveAndFlush(upload);
        parts.saveAndFlush(VideoUploadPart.reserve(upload.getId(), 1, 16777216L, SHA_A, now));
        // part 2는 예약되지 않았습니다.

        complete(upload.getId(), completeBody("etag-1", SHA_A, "etag-2", SHA_B))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PARTS_MISSING"));
    }

    @Test
    void mismatchedSha256IsChecksumMismatch() throws Exception {
        var upload = uploadWithReservedParts(user.getId());

        complete(upload.getId(), completeBody("etag-1", SHA_B, "etag-2", SHA_B))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHECKSUM_MISMATCH"));
    }

    @Test
    void wrongPartCountOrOrderIsValidationError() throws Exception {
        var upload = uploadWithReservedParts(user.getId());

        String onlyOnePart = "{\"parts\":[{\"partNumber\":1,\"etag\":\"\\\"e1\\\"\",\"sha256\":\"" + SHA_A + "\"}]}";
        complete(upload.getId(), onlyOnePart).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        String reversedOrder = "{\"parts\":[{\"partNumber\":2,\"etag\":\"\\\"e2\\\"\",\"sha256\":\"" + SHA_B + "\"},"
                + "{\"partNumber\":1,\"etag\":\"\\\"e1\\\"\",\"sha256\":\"" + SHA_A + "\"}]}";
        complete(upload.getId(), reversedOrder).andExpect(status().isBadRequest());
    }

    @Test
    void nonUploadableStateIsStateConflict() throws Exception {
        var upload = uploadWithReservedParts(user.getId());
        jdbc.update("update video_uploads set status = 'READY'");

        complete(upload.getId(), completeBody("etag-1", SHA_A, "etag-2", SHA_B))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_STATE_CONFLICT"));
    }

    @Test
    void expiredUploadIsGone() throws Exception {
        var upload = uploadWithReservedParts(user.getId());
        jdbc.update("update video_uploads set expires_at = ?", OffsetDateTime.now().minusSeconds(1));

        complete(upload.getId(), completeBody("etag-1", SHA_A, "etag-2", SHA_B))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EXPIRED"));
    }

    @Test
    void otherUsersOrUnknownUploadIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersUpload = uploadWithReservedParts(other.getId());

        complete(othersUpload.getId(), completeBody("e1", SHA_A, "e2", SHA_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void requiresBearerToken() throws Exception {
        var upload = uploadWithReservedParts(user.getId());
        mvc.perform(post("/api/video-uploads/" + upload.getId() + "/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("e1", SHA_A, "e2", SHA_B)))
                .andExpect(status().isUnauthorized());
    }
}
