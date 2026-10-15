package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.Analysis;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisRepository;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUpload;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUploadRepository;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** U05 업로드 취소 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:uploadcancel;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class UploadCancelIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired VideoUploadRepository uploads;
    @Autowired AnalysisRepository analyses;
    @Autowired AccessTokenProvider accessTokens;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        analyses.deleteAll();
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

    private ResultActions cancel(UUID uploadId) throws Exception {
        return mvc.perform(delete("/api/video-uploads/" + uploadId).header("Authorization", bearer));
    }

    @Test
    void cancelsUploadAndAbortsMultipart() throws Exception {
        var upload = initiated(user.getId());

        cancel(upload.getId()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.uploadId").value(upload.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        assertThat(uploads.findById(upload.getId()).orElseThrow().getStatus())
                .isEqualTo(VideoUpload.Status.CANCELED);
    }

    @Test
    void repeatedCancelReturnsSameResult() throws Exception {
        var upload = initiated(user.getId());
        cancel(upload.getId()).andExpect(status().isAccepted());

        cancel(upload.getId()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    @Test
    void readyUploadWithoutAnalysisCanStillBeCanceled() throws Exception {
        var upload = initiated(user.getId());
        upload.beginVerifying();
        upload.ready(null, OffsetDateTime.now());
        uploads.saveAndFlush(upload);

        cancel(upload.getId()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    @Test
    void uploadUsedByAnalysisCannotBeCanceled() throws Exception {
        var upload = initiated(user.getId());
        upload.beginVerifying();
        upload.ready(null, OffsetDateTime.now());
        uploads.saveAndFlush(upload);
        analyses.saveAndFlush(Analysis.queuedForUpload(user.getId(), upload.getId(), OffsetDateTime.now()));

        cancel(upload.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_IN_USE"));
        assertThat(uploads.findById(upload.getId()).orElseThrow().getStatus()).isEqualTo(VideoUpload.Status.READY);
    }

    @Test
    void otherUsersOrUnknownUploadIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersUpload = initiated(other.getId());

        cancel(othersUpload.getId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        cancel(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void requiresBearerToken() throws Exception {
        var upload = initiated(user.getId());
        mvc.perform(delete("/api/video-uploads/" + upload.getId())).andExpect(status().isUnauthorized());
    }
}
