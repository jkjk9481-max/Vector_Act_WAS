package com.personalab.vectoract.vector_act_was;

import com.jayway.jsonpath.JsonPath;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.User;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:me;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
class MeIntegrationTests {
    // 만료 JWT도 실제 검증 키로 서명합니다. 테스트마다 기다리거나 운영 키를 사용할 필요가 없습니다.
    private static final byte[] SIGNING_KEY = new byte[32];
    static { new java.security.SecureRandom().nextBytes(SIGNING_KEY); }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(SIGNING_KEY));
    }
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AccessTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    private User user;

    @BeforeEach
    void prepare() {
        users.deleteAll();
        user = users.saveAndFlush(User.create("actor@example.com", "secret-password-hash", "배우"));
    }

    @Test
    void returnsOnlyTheFourSpecifiedFieldsForTokenOwner() throws Exception {
        var other = users.saveAndFlush(User.create("other@example.com", "other-hash", "다른배우"));
        var response = mvc.perform(get("/api/users/me").param("userId", other.getId().toString())
                        .header("Authorization", "Bearer " + tokens.issue(user.getId())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.name").value("배우"))
                .andExpect(jsonPath("$.data.email").value("actor@example.com"))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> data = JsonPath.read(response, "$.data");
        assertThat(data).containsOnlyKeys("userId", "name", "email", "createdAt");
        assertThat(OffsetDateTime.parse((String) data.get("createdAt")).toInstant().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(user.getCreatedAt().toInstant().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void unknownUserReturnsResourceNotFound() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokens.issue(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void withdrawnUserReturnsResourceNotFound() throws Exception {
        jdbc.update("UPDATE users SET account_status = 'WITHDRAWN' WHERE id = ?", user.getId());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokens.issue(user.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void missingTokenReturnsUnauthorizedAndPreviousAuthenticationDoesNotLeak() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokens.issue(user.getId())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer ", "Bearer broken-token", "Basic abc", "Bearer", ""})
    void rejectsInvalidAuthorization(String authorization) throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", authorization))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCESS_INVALID"));
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() throws Exception {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var otherProvider = new AccessTokenProvider(java.util.Base64.getEncoder().encodeToString(key), "vector-act-test");
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + otherProvider.issue(user.getId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCESS_INVALID"));
    }

    @Test
    void expiredAccessTokenReturnsAccessExpired() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + expiredToken("vector-act-test")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCESS_EXPIRED"));
    }

    @Test
    void expiredTokenWithWrongIssuerIsInvalid() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + expiredToken("wrong-issuer")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCESS_INVALID"));
    }

    @Test
    void authenticationAlsoAppliesOutsideMePath() throws Exception {
        mvc.perform(get("/api/protected-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        // 테스트용 실제 API를 추가하지 않습니다. 인증 통과 후 없는 경로에 대한 404까지 도달하는지 봅니다.
        mvc.perform(get("/api/protected-test").header("Authorization", "Bearer " + tokens.issue(user.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/auth/csrf,200", "POST,/api/auth/signup,400", "POST,/api/auth/login,401",
            "POST,/api/auth/refresh,401", "POST,/api/auth/logout,200"})
    void publicApisKeepTheirOwnBehaviorWithInvalidAccessHeader(String method, String path, int expectedStatus) throws Exception {
        // 공개 API는 잘못된 Access Token 때문에 차단되면 안 됩니다. 기존 입력/Refresh 검증까지 진행해야 합니다.
        var builder = request(org.springframework.http.HttpMethod.valueOf(method), path);
        if ("GET".equals(method)) {
            builder.with(com.personalab.vectoract.vector_act_was.support.ApplicationCsrf.applicationCsrf());
        } else {
            builder.with(csrf());
        }
        var response = mvc.perform(builder.header("Authorization", "Bearer invalid"))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("AUTH_REQUIRED", "ACCESS_INVALID", "ACCESS_EXPIRED");
    }

    private String expiredToken(String issuer) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(user.getId().toString())
                .issuedAt(now.minusSeconds(1000)).expiresAt(now.minusSeconds(100))
                .claim("token_use", "access").build();
        var encoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(SIGNING_KEY, "HmacSHA256")).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
