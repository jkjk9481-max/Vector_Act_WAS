package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.script.business.*;
import com.personalab.vectoract.vector_act_was.domain.script.persistence.ScriptJobRepository;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:scriptextraction;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false",
        // 처리를 요청 스레드에서 동기 실행해 상태 전이를 결정적으로 검증합니다.
        "script.ocr.async=false",
        // 회원마다 별도 카운터를 쓰므로 테스트가 새 회원을 만들면 서로 영향을 주지 않습니다.
        "script.ocr.rate-limit.max-attempts=4"})
@AutoConfigureMockMvc
class ScriptExtractionIntegrationTests {
    private static final String PATH = "/api/script-extractions";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired ScriptJobRepository jobs;
    @Autowired ScriptJobService service;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean InMemoryScriptImageStorage storage;
    @MockitoBean OcrEngine engine;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        jobs.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        storage.keys().forEach(storage::delete);
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    private static byte[] image(String format) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    private ResultActions submit(byte[] content) throws Exception {
        return submit(content, bearer);
    }

    private ResultActions submit(byte[] content, String authorization) throws Exception {
        return mvc.perform(multipart(HttpMethod.POST, PATH)
                .file(new MockMultipartFile("file", "script.png", "image/png", content))
                .header("Authorization", authorization));
    }

    private UUID jobId(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.jobId"));
    }

    private ResultActions poll(UUID id) throws Exception {
        return mvc.perform(get(PATH + "/" + id).header("Authorization", bearer));
    }

    @Test
    void acceptsImageAndProcessesToCompletedResult() throws Exception {
        when(engine.recognize(any())).thenReturn("  추출된 대본입니다.  ");
        var accepted = submit(image("png")).andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.content").value((Object) null))
                .andExpect(jsonPath("$.data.failureCode").value((Object) null))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());
        var id = jobId(accepted);
        String response = accepted.andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("script-ocr/", "sourceObjectKey");

        poll(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(id.toString()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.content").value("추출된 대본입니다."))
                .andExpect(jsonPath("$.data.failureCode").value((Object) null));

        var job = jobs.findById(id).orElseThrow();
        assertThat(job.getUserId()).isEqualTo(user.getId());
        assertThat(Duration.between(job.getCreatedAt(), job.getExpiresAt())).isEqualTo(Duration.ofHours(24));
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getCompletedAt()).isNotNull();
        // 처리가 끝나면 원본 이미지는 저장소에서 지우고 key도 비웁니다.
        assertThat(job.getSourceObjectKey()).isNull();
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void jpegIsAcceptedAndEngineReceivesOriginalBytes() throws Exception {
        byte[] original = image("jpeg");
        when(engine.recognize(any())).thenReturn("본문");
        submit(original).andExpect(status().isAccepted());
        verify(engine).recognize(org.mockito.ArgumentMatchers.eq(original));
    }

    @Test
    void failedEngineResultsAreRecordedWithFailureCodes() throws Exception {
        when(engine.recognize(any())).thenThrow(new OcrFailedException(OcrFailedException.ENGINE_UNAVAILABLE));
        poll(jobId(submit(image("png")))).andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureCode").value("OCR_ENGINE_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.content").value((Object) null));

        doReturn("   ").when(engine).recognize(any());
        poll(jobId(submit(image("png")))).andExpect(jsonPath("$.data.failureCode").value("OCR_NO_TEXT"));

        doReturn("가".repeat(20_001)).when(engine).recognize(any());
        poll(jobId(submit(image("png")))).andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureCode").value("OCR_RESULT_TOO_LONG"));
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void unexpectedEngineErrorIsFailedWithoutLeakingMessage() throws Exception {
        when(engine.recognize(any())).thenThrow(new IllegalStateException("secret-internal-detail"));
        String body = poll(jobId(submit(image("png")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureCode").value("OCR_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret-internal-detail");
    }

    @Test
    void exactlyTwentyThousandCharactersIsAllowed() throws Exception {
        when(engine.recognize(any())).thenReturn("나".repeat(20_000));
        poll(jobId(submit(image("png")))).andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void invalidUploadsAreRejectedWithoutCreatingJobs() throws Exception {
        submit(new byte[0]).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        submit(new byte[10 * 1024 * 1024 + 1]).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
        submit(image("gif")).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        submit("not an image".getBytes(StandardCharsets.UTF_8)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(PATH).header("Authorization", bearer).contentType("application/json").content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(multipart(HttpMethod.POST, PATH).header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(jobs.count()).isZero();
        assertThat(storage.keys()).isEmpty();
        verifyNoInteractions(engine);
    }

    @Test
    void boundarySizeOfTenMebibytesIsAccepted() throws Exception {
        // 크기 검사는 통과하고 내용이 이미지가 아니므로 415가 되어야 합니다(413이 아님).
        submit(new byte[10 * 1024 * 1024]).andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void requiresBearerAndNotCsrf() throws Exception {
        mvc.perform(multipart(HttpMethod.POST, PATH).file(new MockMultipartFile("file", "a.png", "image/png", image("png"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        // 위의 성공 케이스들은 CSRF 없이 Bearer만으로 통과합니다.
        when(engine.recognize(any())).thenReturn("본문");
        submit(image("png")).andExpect(status().isAccepted());
    }

    @Test
    void otherUsersAndUnknownJobsAreNotFoundAndMalformedIdIsValidationError() throws Exception {
        when(engine.recognize(any())).thenReturn("비공개 대본");
        var id = jobId(submit(image("png")));
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른 회원"));
        mvc.perform(get(PATH + "/" + id).header("Authorization", "Bearer " + accessTokens.issue(other.getId())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        poll(UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/not-a-uuid").header("Authorization", bearer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void expiredJobIsGoneAndPurgeRemovesRowAndLeftoverImage() throws Exception {
        when(engine.recognize(any())).thenReturn("본문");
        // 이미지 삭제가 실패해 key가 남은 상태를 만들어 만료 정리가 대신 지우는지 확인합니다.
        doThrow(new IllegalStateException("storage down")).when(storage).delete(any());
        var id = jobId(submit(image("png")));
        reset(storage);
        var leftover = jobs.findById(id).orElseThrow().getSourceObjectKey();
        assertThat(leftover).isNotNull();
        assertThat(storage.contains(leftover)).isTrue();

        // 만료 전에는 정리 대상이 아닙니다.
        assertThat(service.findExpiredIds()).isEmpty();
        assertThat(service.purge(id)).isFalse();

        jdbc.update("UPDATE script_jobs SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), id);
        poll(id).andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("RESULT_EXPIRED"));

        assertThat(service.findExpiredIds()).containsExactly(id);
        assertThat(service.purge(id)).isTrue();
        assertThat(jobs.findById(id)).isEmpty();
        assertThat(storage.contains(leftover)).isFalse();
    }

    @Test
    void storageFailureOnSubmitReturnsServerErrorWithoutJob() throws Exception {
        doThrow(new IllegalStateException("storage down")).when(storage).put(any(), any());
        submit(image("png")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        assertThat(jobs.count()).isZero();
        verifyNoInteractions(engine);
    }

    @Test
    void jobSaveFailureRollsBackAndDeletesStoredImage() throws Exception {
        // 존재하지 않는 회원 ID라도 FK가 없는 테스트 스키마에서는 저장되므로 필수 컬럼 위반으로 저장 실패를 만듭니다.
        jdbc.execute("ALTER TABLE script_jobs ADD CONSTRAINT chk_no_ocr CHECK (job_type <> 'OCR')");
        try {
            submit(image("png")).andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("ALTER TABLE script_jobs DROP CONSTRAINT chk_no_ocr");
        }
        assertThat(jobs.count()).isZero();
        assertThat(storage.keys()).isEmpty();
        verifyNoInteractions(engine);
    }

    @Test
    void rateLimitIsPerUserAndAppliesToSubmitOnly() throws Exception {
        when(engine.recognize(any())).thenReturn("본문");
        UUID id = null;
        for (int i = 0; i < 4; i++) id = jobId(submit(image("png")));
        submit(image("png")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        // 조회(S02)는 제한 대상이 아닙니다.
        poll(id).andExpect(status().isOk());
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른 회원"));
        submit(image("png"), "Bearer " + accessTokens.issue(other.getId())).andExpect(status().isAccepted());
    }

    @Test
    void withdrawnUserIsBlocked() throws Exception {
        jdbc.update("UPDATE users SET account_status = 'WITHDRAWN' WHERE id = ?", user.getId());
        submit(image("png")).andExpect(status().isNotFound());
        assertThat(jobs.count()).isZero();
    }
}
