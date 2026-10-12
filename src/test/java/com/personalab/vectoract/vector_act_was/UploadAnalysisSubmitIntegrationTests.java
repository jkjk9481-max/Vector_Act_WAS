package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.Analysis;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisRepository;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUpload;
import com.personalab.vectoract.vector_act_was.domain.upload.persistence.VideoUploadRepository;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKeyRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** U06 업로드 영상 분석 접수 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:uploadanalysissubmit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class UploadAnalysisSubmitIntegrationTests {
    private static final String PATH = "/api/upload-analyses";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired VideoUploadRepository uploads;
    @Autowired AnalysisRepository analyses;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AccessTokenProvider accessTokens;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        idempotencyKeys.deleteAll();
        analyses.deleteAll();
        uploads.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    private VideoUpload ready(UUID userId) {
        var now = OffsetDateTime.now();
        var upload = uploads.saveAndFlush(VideoUpload.initiate(userId, "practice.mp4", "video/mp4", 20_000_000L,
                "s3-upload-1", "video-uploads/" + userId + "/" + UUID.randomUUID(), now));
        upload.beginVerifying();
        upload.ready(null, now);
        return uploads.saveAndFlush(upload);
    }

    private static String body(UUID uploadId) {
        return "{\"uploadId\":\"" + uploadId + "\",\"scriptContent\":\"오늘은 너에게 솔직히 말하고 싶어.\","
                + "\"situation\":\"친구에게 이별을 고하는 상황\"}";
    }

    private ResultActions submit(String body, String key) throws Exception {
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", bearer);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    @Test
    void submitsReadyUploadAsQueuedAnalysis() throws Exception {
        var upload = ready(user.getId());

        submit(body(upload.getId()), key()).andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.analysisId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.expiresAt").value((Object) null))
                .andExpect(jsonPath("$.data.failureCode").value((Object) null));

        var analysis = analyses.findByUploadId(upload.getId()).orElseThrow();
        assertThat(analysis.getUserId()).isEqualTo(user.getId());
        assertThat(analysis.getSource()).isEqualTo(Analysis.SOURCE_UPLOADED_VIDEO);
        assertThat(analysis.getStatus()).isEqualTo(Analysis.Status.QUEUED);
        assertThat(analysis.getAttempt()).isEqualTo(1);
    }

    @Test
    void sameKeyAndBodyReplaysSameResponseWithoutCreatingAnother() throws Exception {
        var upload = ready(user.getId());
        String key = key();
        String first = submit(body(upload.getId()), key).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String second = submit(body(upload.getId()), key).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(analyses.count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsIdempotencyConflict() throws Exception {
        var upload = ready(user.getId());
        var other = ready(user.getId());
        String key = key();
        submit(body(upload.getId()), key).andExpect(status().isAccepted());

        submit(body(other.getId()), key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(analyses.count()).isEqualTo(1);
    }

    @Test
    void notReadyUploadIsUploadNotReady() throws Exception {
        var now = OffsetDateTime.now();
        var upload = uploads.saveAndFlush(VideoUpload.initiate(user.getId(), "practice.mp4", "video/mp4",
                20_000_000L, "s3-upload-1", "video-uploads/" + user.getId() + "/" + UUID.randomUUID(), now));

        submit(body(upload.getId()), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_NOT_READY"));
        assertThat(analyses.count()).isZero();
    }

    @Test
    void alreadyAnalyzedUploadIsUploadInUse() throws Exception {
        var upload = ready(user.getId());
        analyses.saveAndFlush(Analysis.queuedForUpload(user.getId(), upload.getId(), OffsetDateTime.now()));

        submit(body(upload.getId()), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_IN_USE"));
        assertThat(analyses.count()).isEqualTo(1);
    }

    @Test
    void otherUsersOrUnknownUploadIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersUpload = ready(other.getId());

        submit(body(othersUpload.getId()), key()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        submit(body(UUID.randomUUID()), key()).andExpect(status().isNotFound());
    }

    @Test
    void invalidInputIsValidationError() throws Exception {
        var upload = ready(user.getId());
        String longScript = "가".repeat(20001);
        String[] bodies = {
                body(upload.getId()).replace("오늘은 너에게 솔직히 말하고 싶어.", ""),
                body(upload.getId()).replace("오늘은 너에게 솔직히 말하고 싶어.", longScript),
                "{}", "not-json"};
        for (String b : bodies) {
            submit(b, key()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        assertThat(analyses.count()).isZero();
    }

    @Test
    void missingOrMalformedIdempotencyKeyIsValidationError() throws Exception {
        var upload = ready(user.getId());
        submit(body(upload.getId()), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(analyses.count()).isZero();
    }

    @Test
    void requiresBearerToken() throws Exception {
        var upload = ready(user.getId());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(upload.getId()))
                        .header("Idempotency-Key", key()))
                .andExpect(status().isUnauthorized());
    }
}
