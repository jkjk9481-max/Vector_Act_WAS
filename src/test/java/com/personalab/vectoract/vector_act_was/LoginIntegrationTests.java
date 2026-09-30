package com.personalab.vectoract.vector_act_was;

import com.jayway.jsonpath.JsonPath;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:login;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
class LoginIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired PasswordEncoder passwords;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired RefreshTokenGenerator generator;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String clientIp;
    private static final String BODY = """
            {"email":"  ACTOR@example.com  ","password":" Password123! "}
            """;

    @BeforeEach
    void prepare() {
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create("actor@example.com", passwords.encode(" Password123! "), "배우"));
        // 테스트끼리 요청 제한 횟수를 공유하지 않게 각 테스트에 별도 주소를 부여합니다.
        clientIp = UUID.randomUUID().toString();
    }

    @Test
    void issuesJwtAndCookieAndStoresOnlyRefreshHash() throws Exception {
        var response = login(BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.user.name").value("배우"))
                .andExpect(jsonPath("$.data.user.email").value("actor@example.com"))
                .andExpect(jsonPath("$.data.user.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        String access = JsonPath.read(response.getContentAsString(), "$.data.accessToken");
        var jwt = accessTokens.verify(access);
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        var cookie = response.getCookie("refreshToken");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getMaxAge()).isEqualTo(14 * 24 * 60 * 60);
        assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Lax");
        assertThat(response.getContentAsString()).doesNotContain(cookie.getValue());
        var stored = refreshTokens.findAll().getFirst();
        assertThat(stored.getTokenHash()).isEqualTo(generator.hash(cookie.getValue())).hasSize(64);
        assertThat(stored.getTokenHash()).isNotEqualTo(cookie.getValue()).isNotEqualTo(access);
        assertThat(stored.getUser().getId()).isEqualTo(user.getId());
        assertThat(stored.getFamilyId()).isNotNull();
        assertThat(stored.getRevokedAt()).isNull();
        assertThat(stored.getReplacedByTokenId()).isNull();
        assertThat(Duration.between(stored.getIssuedAt(), stored.getExpiresAt())).isEqualTo(Duration.ofDays(14));
    }

    @Test
    void rejectsWrongPasswordUnknownEmailAndWithdrawnAccount() throws Exception {
        // 앞뒤 공백도 비밀번호의 일부입니다. 제거한 값이나 소문자로 변환한 값은 실패해야 합니다.
        for (String body : new String[]{BODY.replace(" Password123! ", "Password123!"),
                BODY.replace(" Password123! ", " password123! "),
                BODY.replace("ACTOR@example.com", "unknown@example.com"),
                BODY.replace(" Password123! ", "가".repeat(25))}) {
            assertInvalid(body);
        }
        jdbc.update("UPDATE users SET account_status = 'WITHDRAWN' WHERE id = ?", user.getId());
        assertInvalid(BODY);
    }

    @Test
    void rejectsMissingAndMalformedRequestsWithoutLeakingPassword() throws Exception {
        for (String body : new String[]{"{}", "{", "", "{\"email\":\"actor@example.com\"}",
                "{\"password\":\"secret\"}", BODY.replace("ACTOR@example.com", "invalid"),
                BODY.replace(" Password123! ", ""), BODY.replace("ACTOR@example.com", "a".repeat(255) + "@example.com")}) {
            var response = assertInvalid(body).andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("secret", "Password123");
        }
    }

    @Test
    void eleventhRequestIsRateLimitedEvenWhenBodyIsInvalid() throws Exception {
        for (int i = 0; i < 10; i++) assertInvalid("{}");
        login(BODY).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(refreshTokens.count()).isZero();
    }

    @Test
    void eachLoginCreatesIndependentRefreshTokenAndFamily() throws Exception {
        var first = login(BODY).andExpect(status().isOk()).andReturn().getResponse().getCookie("refreshToken");
        var second = login(BODY).andExpect(status().isOk()).andReturn().getResponse().getCookie("refreshToken");
        assertThat(first.getValue()).isNotEqualTo(second.getValue());
        assertThat(refreshTokens.findAll()).extracting(RefreshToken::getFamilyId).doesNotHaveDuplicates().hasSize(2);
    }

    @Test
    void deletingUserInDatabaseCascadesToRefreshTokens() throws Exception {
        login(BODY).andExpect(status().isOk());
        login(BODY).andExpect(status().isOk());
        assertThat(refreshTokens.count()).isEqualTo(2);
        // JPA로 토큰을 먼저 지우지 않습니다. DB의 FK가 실제로 연쇄 삭제하는지 검증합니다.
        jdbc.update("DELETE FROM users WHERE id = ?", user.getId());
        assertThat(refreshTokens.count()).isZero();
    }

    @Test
    void deletingRefreshTokenDoesNotDeleteUser() throws Exception {
        login(BODY).andExpect(status().isOk());
        refreshTokens.deleteAll();
        assertThat(users.existsById(user.getId())).isTrue();
    }

    @Test
    void databaseEnforcesExactly64CharactersForTokenHash() throws Exception {
        login(BODY).andExpect(status().isOk());
        for (int length : new int[]{63, 65}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE refresh_tokens SET token_hash = ?", "a".repeat(length)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThat(refreshTokens.findAll().getFirst().getTokenHash()).hasSize(64);
    }

    @Test
    void storageFailureDoesNotIssueTokensOrCookie() throws Exception {
        jdbc.execute("ALTER TABLE refresh_tokens ADD CONSTRAINT test_reject_refresh CHECK (token_hash = 'forbidden')");
        try {
            login(BODY).andExpect(status().isInternalServerError())
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(jsonPath("$.data").isEmpty());
            assertThat(refreshTokens.count()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE refresh_tokens DROP CONSTRAINT test_reject_refresh");
        }
    }

    private ResultActions assertInvalid(String body) throws Exception {
        var result = login(body).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(refreshTokens.count()).isZero();
        return result;
    }

    private ResultActions login(String body) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> { request.setRemoteAddr(clientIp); return request; }));
    }
}
