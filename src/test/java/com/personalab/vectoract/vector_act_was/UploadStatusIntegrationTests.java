package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUpload;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUploadRepository;
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

/** U04 업로드 상태 조회 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:uploadstatus;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class UploadStatusIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired VideoUploadRepository uploads;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        uploads.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    private VideoUpload initiated(UUID userId) {
        var now = OffsetDateTime.now();
        return uploads.saveAndFlush(VideoUpload.initiate(userId, "practice.mp4", "video/mp4", 20_000_000L,
                "s3-upload-1", "video-uploads/" + userId + "/" + UUID.randomUUID(), now));
    }

    private ResultActions fetchStatus(UUID uploadId) throws Exception {
        return mvc.perform(get("/api/video-uploads/" + uploadId).header("Authorization", bearer));
    }

    @Test
    void returnsCurrentStatus() throws Exception {
        var upload = initiated(user.getId());

        fetchStatus(upload.getId()).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.uploadId").value(upload.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("INITIATED"))
                .andExpect(jsonPath("$.data.partCount").value(2));
    }

    @Test
    void readyStatusIsReturnedEvenAfterExpiry() throws Exception {
        var upload = initiated(user.getId());
        jdbc.update("update video_uploads set status = 'READY', expires_at = ?",
                OffsetDateTime.now().minusDays(1));

        fetchStatus(upload.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));
    }

    @Test
    void expiredUnfinishedUploadIsGone() throws Exception {
        var upload = initiated(user.getId());
        jdbc.update("update video_uploads set expires_at = ?", OffsetDateTime.now().minusSeconds(1));

        fetchStatus(upload.getId()).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EXPIRED"));
    }

    @Test
    void otherUsersOrUnknownUploadIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersUpload = initiated(other.getId());

        fetchStatus(othersUpload.getId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        fetchStatus(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void requiresBearerToken() throws Exception {
        var upload = initiated(user.getId());
        mvc.perform(get("/api/video-uploads/" + upload.getId())).andExpect(status().isUnauthorized());
    }
}
