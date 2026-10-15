package com.personalab.vectoract.vector_act_was;

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
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** U01 대용량 업로드 시작 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:videouploadcreate;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class VideoUploadCreateIntegrationTests {
    private static final String PATH = "/api/video-uploads";
    private static final String BODY = """
            {"fileName":"practice.mp4","contentType":"video/mp4","sizeBytes":20000000}""";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired VideoUploadRepository uploads;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AccessTokenProvider accessTokens;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        idempotencyKeys.deleteAll();
        uploads.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    private ResultActions create(String body, String key) throws Exception {
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", bearer);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    @Test
    void createsUploadWithComputedPartCount() throws Exception {
        create(BODY, key()).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.uploadId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("INITIATED"))
                .andExpect(jsonPath("$.data.partSizeBytes").value(16777216))
                .andExpect(jsonPath("$.data.partCount").value(2))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.failureCode").value((Object) null));

        var saved = uploads.findAll().getFirst();
        assertThat(saved.getUserId()).isEqualTo(user.getId());
        assertThat(saved.getFileName()).isEqualTo("practice.mp4");
        assertThat(saved.getContentType()).isEqualTo("video/mp4");
        assertThat(saved.getSizeBytes()).isEqualTo(20000000L);
        assertThat(saved.getS3UploadId()).isNotBlank();
        assertThat(saved.getObjectKey()).startsWith("video-uploads/" + user.getId() + "/");
        assertThat(saved.getStatus()).isEqualTo(VideoUpload.Status.INITIATED);
    }

    @Test
    void exactMultipleOfPartSizeComputesExactPartCount() throws Exception {
        create("""
                {"fileName":"a.mp4","contentType":"video/mp4","sizeBytes":16777216}""", key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.partCount").value(1));
    }

    @Test
    void sameKeyAndBodyReplaysSameResponseWithoutCreatingAnother() throws Exception {
        String key = key();
        String first = create(BODY, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = create(BODY, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(uploads.count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsIdempotencyConflict() throws Exception {
        String key = key();
        create(BODY, key).andExpect(status().isCreated());

        create(BODY.replace("20000000", "30000000"), key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(uploads.count()).isEqualTo(1);
    }

    @Test
    void sizeAboveTwoGibIs413AndBoundaryIsAccepted() throws Exception {
        create("""
                {"fileName":"a.mp4","contentType":"video/mp4","sizeBytes":2147483649}""", key())
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
        create("""
                {"fileName":"a.mp4","contentType":"video/mp4","sizeBytes":2147483648}""", key())
                .andExpect(status().isCreated());
    }

    @Test
    void unsupportedContentTypeIs415() throws Exception {
        create(BODY.replace("video/mp4", "video/avi"), key()).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        assertThat(uploads.count()).isZero();
    }

    @Test
    void invalidInputIsValidationError() throws Exception {
        String[] bodies = {
                BODY.replace("\"practice.mp4\"", "\"\""),
                BODY.replace("\"practice.mp4\"", "\"" + "a".repeat(256) + "\""),
                BODY.replace("20000000", "0"),
                "{}", "not-json"};
        for (String body : bodies) {
            create(body, key()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        assertThat(uploads.count()).isZero();
    }

    @Test
    void missingOrMalformedIdempotencyKeyIsValidationError() throws Exception {
        create(BODY, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        create(BODY, "not-a-uuid").andExpect(status().isBadRequest());
        assertThat(uploads.count()).isZero();
    }

    @Test
    void requiresBearerToken() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isUnauthorized());
        assertThat(uploads.count()).isZero();
    }
}
