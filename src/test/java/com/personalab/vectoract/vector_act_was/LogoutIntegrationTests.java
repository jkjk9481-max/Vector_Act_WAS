package com.personalab.vectoract.vector_act_was;

import com.jayway.jsonpath.JsonPath;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:logout;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
class LogoutIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository tokens;
    @Autowired RefreshTokenGenerator generator;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String raw;
    private RefreshToken original;

    @BeforeEach
    void prepare() {
        tokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create("actor@example.com", "unused-password-hash", "배우"));
        raw = generator.generate();
        original = tokens.saveAndFlush(RefreshToken.create(user, generator.hash(raw), OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void a01SessionLogoutRevokesEntireFamilyAndDeletesCookie() throws Exception {
        var successor = tokens.saveAndFlush(original.successor(generator.hash(generator.generate()), OffsetDateTime.now()));
        var csrfResult = mvc.perform(get("/api/auth/csrf").with(com.personalab.vectoract.vector_act_was.support.ApplicationCsrf.applicationCsrf())).andExpect(status().isOk()).andReturn();
        String csrfToken = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.data.csrfToken");
        var response = assertAccepted(mvc.perform(post("/api/auth/logout")
                .session((MockHttpSession) csrfResult.getRequest().getSession(false))
                .header("X-CSRF-TOKEN", csrfToken).cookie(new Cookie("refreshToken", raw))))
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(raw, "accessToken", "refreshToken");
        assertRevoked(original);
        assertRevoked(successor);
        assertThat(tokens.count()).isEqualTo(2);
    }

    @Test
    void preservesOtherFamilyForSameUserAndOtherUser() throws Exception {
        var otherFamily = tokens.saveAndFlush(RefreshToken.create(user, generator.hash(generator.generate()), OffsetDateTime.now()));
        var otherUser = users.saveAndFlush(User.create("other@example.com", "unused", "다른 배우"));
        var otherUserToken = tokens.saveAndFlush(RefreshToken.create(otherUser, generator.hash(generator.generate()), OffsetDateTime.now()));
        assertAccepted(logout(raw));
        assertRevoked(original);
        assertActive(otherFamily);
        assertActive(otherUserToken);
    }

    @Test
    void acceptsMissingCookie() throws Exception {
        assertAccepted(mvc.perform(post("/api/auth/logout").with(csrf())));
        assertActive(original);
    }

    @Test
    void acceptsEmptyCookie() throws Exception {
        assertAccepted(logout(""));
        assertActive(original);
    }

    @Test
    void acceptsUnknownCookie() throws Exception {
        assertAccepted(logout("unknown-token"));
        assertActive(original);
    }

    @Test
    void revokedAncestorStillRevokesRotatedSuccessorAndRepeatedLogoutSucceeds() throws Exception {
        var refreshed = refresh().andExpect(status().isOk()).andReturn().getResponse().getCookie("refreshToken");
        var successor = tokens.findByTokenHash(generator.hash(refreshed.getValue())).orElseThrow();
        var revokedAt = tokens.findById(original.getId()).orElseThrow().getRevokedAt();
        assertAccepted(logout(raw));
        assertAccepted(logout(raw));
        assertRevoked(successor);
        assertThat(tokens.findById(original.getId()).orElseThrow().getRevokedAt()).isEqualTo(revokedAt);
    }

    @Test
    void expiredAncestorStillRevokesActiveSuccessor() throws Exception {
        var successor = tokens.saveAndFlush(original.successor(generator.hash(generator.generate()), OffsetDateTime.now()));
        jdbc.update("UPDATE refresh_tokens SET expires_at = CURRENT_TIMESTAMP WHERE id = ?", original.getId());
        assertAccepted(logout(raw));
        assertRevoked(original);
        assertRevoked(successor);
    }

    @Test
    void missingAndWrongCsrfDoNotRevokeOrDeleteCookie() throws Exception {
        mvc.perform(post("/api/auth/logout").cookie(new Cookie("refreshToken", raw)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/api/auth/logout").with(csrf().useInvalidToken()).cookie(new Cookie("refreshToken", raw)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertActive(original);
    }

    @RepeatedTest(5)
    void concurrentRefreshAndLogoutLeaveNoActiveTokenInFamily() throws Exception {
        var other = tokens.saveAndFlush(RefreshToken.create(user, generator.hash(generator.generate()), OffsetDateTime.now()));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var refreshing = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return refresh().andReturn().getResponse();
            });
            var loggingOut = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return assertAccepted(logout(raw)).andReturn().getResponse();
            });
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(loggingOut.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
            var refreshed = refreshing.get(15, TimeUnit.SECONDS);
            assertThat(refreshed.getStatus()).isIn(200, 401);
            if (refreshed.getStatus() == 401) {
                assertThat((String) JsonPath.read(refreshed.getContentAsString(), "$.error.code")).isEqualTo("REFRESH_REUSED");
            }
        }
        assertThat(tokens.findByFamilyIdAndRevokedAtIsNull(original.getFamilyId())).isEmpty();
        assertActive(other);
    }

    @Test
    void refreshAfterLogoutCannotRotate() throws Exception {
        assertAccepted(logout(raw));
        refresh().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("REFRESH_REUSED"));
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void databaseFailureRollsBackWholeFamilyAndDoesNotReturnSuccessCookie() throws Exception {
        var successor = tokens.saveAndFlush(original.successor(generator.hash(generator.generate()), OffsetDateTime.now()));
        // The first token can be updated, but updating the successor violates a real DB constraint.
        jdbc.execute("ALTER TABLE refresh_tokens ADD CONSTRAINT test_logout_failure CHECK (id <> '" + successor.getId() + "' OR revoked_at IS NULL)");
        try {
            logout(raw).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
            assertActive(original);
            assertActive(successor);
            assertThat(tokens.count()).isEqualTo(2);
        } finally {
            jdbc.execute("ALTER TABLE refresh_tokens DROP CONSTRAINT test_logout_failure");
        }
        assertAccepted(logout(raw));
        assertRevoked(original);
        assertRevoked(successor);
    }

    private ResultActions logout(String value) throws Exception {
        return mvc.perform(post("/api/auth/logout").with(csrf()).cookie(new Cookie("refreshToken", value)));
    }

    private ResultActions refresh() throws Exception {
        return mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(new Cookie("refreshToken", raw)));
    }

    private ResultActions assertAccepted(ResultActions result) throws Exception {
        return result.andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"data\":{\"accepted\":true},\"message\":\"OK\",\"error\":null}"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(cookie().value("refreshToken", ""))
                .andExpect(cookie().maxAge("refreshToken", 0))
                .andExpect(cookie().path("refreshToken", "/api/auth"))
                .andExpect(cookie().httpOnly("refreshToken", true))
                .andExpect(cookie().secure("refreshToken", true))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Lax")));
    }

    private void assertRevoked(RefreshToken token) {
        assertThat(tokens.findById(token.getId()).orElseThrow().getRevokedAt()).isNotNull();
    }

    private void assertActive(RefreshToken token) {
        assertThat(tokens.findById(token.getId()).orElseThrow().getRevokedAt()).isNull();
    }
}
