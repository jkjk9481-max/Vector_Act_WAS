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

/** U02 Part 업로드 URL 발급 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:partuploadurl;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class PartUploadUrlIntegrationTests {
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

    /** 20,000,000바이트 → 16MiB + 나머지, partCount=2인 INITIATED 업로드를 만듭니다. */
    private VideoUpload initiated(UUID userId) {
        var now = OffsetDateTime.now();
        return uploads.saveAndFlush(VideoUpload.initiate(userId, "practice.mp4", "video/mp4", 20_000_000L,
                "s3-upload-1", "video-uploads/" + userId + "/" + UUID.randomUUID(), now));
    }

    private static String body(long sizeBytes, String sha256) {
        return "{\"sizeBytes\":" + sizeBytes + ",\"sha256\":\"" + sha256 + "\"}";
    }

    private ResultActions issue(UUID uploadId, int partNumber, String body) throws Exception {
        return mvc.perform(post("/api/video-uploads/" + uploadId + "/parts/" + partNumber + "/upload-url")
                .contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", bearer));
    }

    @Test
    void reservesFirstPartAndBeginsUploading() throws Exception {
        var upload = initiated(user.getId());

        issue(upload.getId(), 1, body(16777216, SHA_A)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.partNumber").value(1))
                .andExpect(jsonPath("$.data.putUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.requiredHeaders").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());

        var part = parts.findByUploadIdAndPartNumber(upload.getId(), 1).orElseThrow();
        assertThat(part.getStatus()).isEqualTo(VideoUploadPart.Status.RESERVED);
        assertThat(uploads.findById(upload.getId()).orElseThrow().getStatus()).isEqualTo(VideoUpload.Status.UPLOADING);
    }

    @Test
    void lastPartAcceptsRemainderSize() throws Exception {
        var upload = initiated(user.getId());
        long remainder = 20_000_000L - 16777216L;

        issue(upload.getId(), 2, body(remainder, SHA_A)).andExpect(status().isOk());
        issue(upload.getId(), 2, body(16777216, SHA_A)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void sameContentAgainReissuesUrl() throws Exception {
        var upload = initiated(user.getId());
        issue(upload.getId(), 1, body(16777216, SHA_A)).andExpect(status().isOk());

        issue(upload.getId(), 1, body(16777216, SHA_A)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.putUrl").isNotEmpty());
        assertThat(parts.count()).isEqualTo(1);
    }

    @Test
    void differentContentForSameNumberIsPartConflict() throws Exception {
        var upload = initiated(user.getId());
        issue(upload.getId(), 1, body(16777216, SHA_A)).andExpect(status().isOk());

        issue(upload.getId(), 1, body(16777216, SHA_B)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PART_CONFLICT"));
    }

    @Test
    void partNumberOutOfRangeIsValidationError() throws Exception {
        var upload = initiated(user.getId());
        issue(upload.getId(), 0, body(16777216, SHA_A)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        issue(upload.getId(), 3, body(16777216, SHA_A)).andExpect(status().isBadRequest());
    }

    @Test
    void nonUploadableStateIsStateConflict() throws Exception {
        var upload = initiated(user.getId());
        jdbc.update("update video_uploads set status = 'READY'");

        issue(upload.getId(), 1, body(16777216, SHA_A)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_STATE_CONFLICT"));
    }

    @Test
    void expiredUploadIsGone() throws Exception {
        var upload = initiated(user.getId());
        jdbc.update("update video_uploads set expires_at = ?", OffsetDateTime.now().minusSeconds(1));

        issue(upload.getId(), 1, body(16777216, SHA_A)).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EXPIRED"));
    }

    @Test
    void otherUsersOrUnknownUploadIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersUpload = initiated(other.getId());

        issue(othersUpload.getId(), 1, body(16777216, SHA_A)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        issue(UUID.randomUUID(), 1, body(16777216, SHA_A)).andExpect(status().isNotFound());
    }

    @Test
    void requiresBearerToken() throws Exception {
        var upload = initiated(user.getId());
        mvc.perform(post("/api/video-uploads/" + upload.getId() + "/parts/1/upload-url")
                        .contentType(MediaType.APPLICATION_JSON).content(body(16777216, SHA_A)))
                .andExpect(status().isUnauthorized());
    }
}
