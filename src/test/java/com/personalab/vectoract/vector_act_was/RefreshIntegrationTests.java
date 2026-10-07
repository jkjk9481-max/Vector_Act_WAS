package com.personalab.vectoract.vector_act_was;

import com.jayway.jsonpath.JsonPath;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:refresh;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
class RefreshIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository tokens;
    @Autowired RefreshTokenGenerator generator;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String raw;
    private RefreshToken original;

    @BeforeEach
    void prepare() {
        tokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create("actor@example.com", "unused-password-hash", "배우"));
        user = users.findById(user.getId()).orElseThrow();
        raw = generator.generate();
        original = tokens.saveAndFlush(RefreshToken.create(user, generator.hash(raw), OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void refreshWithA01SessionIssuesJwtAndRotatesCookieAndDatabase() throws Exception {
        var csrfResult = mvc.perform(get("/api/auth/csrf").with(com.personalab.vectoract.vector_act_was.support.ApplicationCsrf.applicationCsrf())).andExpect(status().isOk()).andReturn();
        String csrfToken = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.data.csrfToken");
        String oldAccess = accessTokens.issue(user.getId());
        var response = mvc.perform(post("/api/auth/refresh")
                        .session((MockHttpSession) csrfResult.getRequest().getSession(false))
                        .header("X-CSRF-TOKEN", csrfToken).cookie(new Cookie("refreshToken", raw)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.user.name").value(user.getName()))
                .andExpect(jsonPath("$.data.user.email").value(user.getEmail()))
                .andExpect(jsonPath("$.data.user.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache")).andReturn().getResponse();
        String access = JsonPath.read(response.getContentAsString(), "$.data.accessToken");
        assertThat(access).isNotEqualTo(oldAccess);
        var jwt = accessTokens.verify(access);
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofSeconds(900));
        String createdAt = JsonPath.read(response.getContentAsString(), "$.data.user.createdAt");
        assertThat(OffsetDateTime.parse(createdAt).toInstant()).isEqualTo(user.getCreatedAt().toInstant());
        var cookie = response.getCookie("refreshToken");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotEqualTo(raw);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getMaxAge()).isEqualTo(14 * 24 * 60 * 60);
        assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Lax");
        assertThat(response.getContentAsString()).doesNotContain(raw, cookie.getValue(), "passwordHash");
        var old = tokens.findById(original.getId()).orElseThrow();
        var replacement = tokens.findByTokenHash(generator.hash(cookie.getValue())).orElseThrow();
        assertThat(tokens.count()).isEqualTo(2);
        assertThat(old.getRevokedAt()).isNotNull();
        assertThat(old.getReplacedByTokenId()).isEqualTo(replacement.getId());
        assertThat(replacement.getFamilyId()).isEqualTo(old.getFamilyId());
        assertThat(replacement.getTokenHash()).isNotEqualTo(old.getTokenHash());
        assertThat(replacement.getRevokedAt()).isNull();
        assertThat(replacement.getReplacedByTokenId()).isNull();
        assertThat(Duration.between(replacement.getIssuedAt(), replacement.getExpiresAt())).isEqualTo(Duration.ofDays(14));
    }

    @Test
    void reuseCommitsRevocationOfOnlyTheCompromisedFamily() throws Exception {
        var other = tokens.saveAndFlush(RefreshToken.create(user, generator.hash(generator.generate()), OffsetDateTime.now()));
        String next = refresh(raw).andExpect(status().isOk()).andReturn().getResponse().getCookie("refreshToken").getValue();
        String newest = refresh(next).andExpect(status().isOk()).andReturn().getResponse().getCookie("refreshToken").getValue();
        assertError(raw, "REFRESH_REUSED");
        assertThat(tokens.findByTokenHash(generator.hash(newest)).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(tokens.findById(other.getId()).orElseThrow().getRevokedAt()).isNull();
        assertError(newest, "REFRESH_REUSED");
        assertThat(tokens.count()).isEqualTo(4);
    }

    @Test
    void rejectsMissingEmptyAndUnknownCookies() throws Exception {
        mvc.perform(post("/api/auth/refresh").with(csrf())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_INVALID"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertError("", "REFRESH_INVALID");
        assertError("unknown", "REFRESH_INVALID");
        assertUnchanged();
    }

    @Test
    void rejectsExpiredTokenBeforeCheckingRevocation() throws Exception {
        jdbc.update("UPDATE refresh_tokens SET expires_at = CURRENT_TIMESTAMP WHERE id = ?", original.getId());
        assertError(raw, "REFRESH_EXPIRED");
        jdbc.update("UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", original.getId());
        assertError(raw, "REFRESH_EXPIRED");
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void rejectsWithdrawnUserWithRefreshInvalid() throws Exception {
        jdbc.update("UPDATE users SET account_status = 'WITHDRAWN' WHERE id = ?", user.getId());
        assertError(raw, "REFRESH_INVALID");
        assertUnchanged();
    }

    @Test
    void rejectsMissingAndMismatchedCsrf() throws Exception {
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refreshToken", raw)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refreshToken", raw)).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertUnchanged();
    }

    @Test
    void insertFailureRollsBackAndDoesNotIssueCookie() throws Exception {
        jdbc.execute("ALTER TABLE refresh_tokens ADD CONSTRAINT test_insert_failure CHECK (token_hash = '" + original.getTokenHash() + "')");
        try {
            assertStorageFailure();
        } finally {
            jdbc.execute("ALTER TABLE refresh_tokens DROP CONSTRAINT test_insert_failure");
        }
    }

    @Test
    void rotationUpdateFailureRollsBackAlreadyInsertedSuccessor() throws Exception {
        jdbc.execute("ALTER TABLE refresh_tokens ADD CONSTRAINT test_rotation_failure CHECK (revoked_at IS NULL)");
        try {
            assertStorageFailure();
        } finally {
            jdbc.execute("ALTER TABLE refresh_tokens DROP CONSTRAINT test_rotation_failure");
        }
    }

    @Test
    void simultaneousRequestsCannotCreateTwoActiveSuccessors() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> request = () -> {
                start.await();
                return refresh(raw).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 401);
        }
        assertThat(tokens.count()).isEqualTo(2);
        assertThat(tokens.findByFamilyIdAndRevokedAtIsNull(original.getFamilyId())).isEmpty();
    }

    private ResultActions refresh(String value) throws Exception {
        return mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(new Cookie("refreshToken", value)));
    }

    private void assertError(String value, String code) throws Exception {
        refresh(value).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value(code))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    private void assertStorageFailure() throws Exception {
        refresh(raw).andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Set-Cookie")).andExpect(jsonPath("$.data").isEmpty());
        assertUnchanged();
    }

    private void assertUnchanged() {
        assertThat(tokens.count()).isEqualTo(1);
        var token = tokens.findById(original.getId()).orElseThrow();
        assertThat(token.getRevokedAt()).isNull();
        assertThat(token.getReplacedByTokenId()).isNull();
    }
}
