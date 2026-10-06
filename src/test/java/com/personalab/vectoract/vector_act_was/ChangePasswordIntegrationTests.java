package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.member.business.ChangePasswordService;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.ChangePasswordRequest;
import com.personalab.vectoract.vector_act_was.global.auth.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:change-password;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
class ChangePasswordIntegrationTests {
    private static final byte[] SIGNING_KEY = new byte[32];
    static { new java.security.SecureRandom().nextBytes(SIGNING_KEY); }

    @org.springframework.test.context.DynamicPropertySource
    static void jwtProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(SIGNING_KEY));
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository tokens;
    @Autowired PasswordEncoder passwords;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired RefreshTokenGenerator generator;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean ChangePasswordService service;
    private User user;
    private String access;
    private String rawRefresh;
    private RefreshToken original;
    private static final String CURRENT = "CurrentPassword!1";
    private static final String NEXT = "NewPassword!2";
    private static final OffsetDateTime OLD_TIME = OffsetDateTime.parse("2020-01-01T00:00:00Z");

    @BeforeEach
    void prepare() {
        tokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create("actor@example.com", passwords.encode(CURRENT), "배우"));
        jdbc.update("UPDATE users SET updated_at = ? WHERE id = ?", OLD_TIME, user.getId());
        access = accessTokens.issue(user.getId());
        rawRefresh = generator.generate();
        original = tokens.saveAndFlush(RefreshToken.create(user, generator.hash(rawRefresh), OffsetDateTime.now()));
    }

    @Test
    void changesOnlyOwnerRevokesEveryDeviceAndRequiresNewPasswordForLogin() throws Exception {
        var other = users.saveAndFlush(User.create("other@example.com", passwords.encode(CURRENT), "다른회원"));
        var otherToken = token(other);
        var deviceTwo = token(user);
        var alreadyRevoked = token(user);
        jdbc.update("UPDATE refresh_tokens SET revoked_at = ?, replaced_by_token_id = ? WHERE id = ?",
                OLD_TIME, deviceTwo.getId(), alreadyRevoked.getId());
        var body = new HashMap<String, String>(body(CURRENT, NEXT, NEXT));
        body.put("userId", other.getId().toString());
        var response = mvc.perform(patch("/api/users/me/password").param("userId", other.getId().toString())
                        .header("Authorization", "Bearer " + access).contentType("application/json")
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(CURRENT, NEXT, "passwordHash");
        assertThat(response.getCookie("refreshToken").getMaxAge()).isZero();
        assertThat(response.getCookie("refreshToken").getPath()).isEqualTo("/api/auth");
        assertThat(response.getCookie("refreshToken").isHttpOnly()).isTrue();
        var saved = users.findById(user.getId()).orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo(NEXT).isNotEqualTo(user.getPasswordHash());
        assertThat(passwords.matches(NEXT, saved.getPasswordHash())).isTrue();
        assertThat(saved.getUpdatedAt()).isAfter(OLD_TIME);
        assertThat(saved.getName()).isEqualTo(user.getName());
        assertThat(saved.getEmail()).isEqualTo(user.getEmail());
        assertThat(saved.getAccountStatus()).isEqualTo(User.AccountStatus.ACTIVE);
        assertThat(tokens.findByUserIdAndRevokedAtIsNull(user.getId())).isEmpty();
        assertThat(tokens.findById(alreadyRevoked.getId()).orElseThrow().getRevokedAt()).isEqualTo(OLD_TIME);
        assertThat(tokens.findById(alreadyRevoked.getId()).orElseThrow().getReplacedByTokenId()).isEqualTo(deviceTwo.getId());
        assertThat(tokens.findById(otherToken.getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(users.findById(other.getId()).orElseThrow().getPasswordHash()).isEqualTo(other.getPasswordHash());
        refresh().andExpect(status().isUnauthorized());
        login(CURRENT).andExpect(status().isUnauthorized());
        login(NEXT).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 32})
    void acceptsPasswordLengthBoundaries(int length) throws Exception {
        String password = "a".repeat(length);
        change(CURRENT, password, password).andExpect(status().isOk());
        assertThat(passwords.matches(password, users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void accepts72Utf8BytesAndPreservesWhitespace() throws Exception {
        String password = "가".repeat(23) + "   ";
        change(CURRENT, password, password).andExpect(status().isOk());
        assertThat(passwords.matches(password, users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "123456789012345678901234567890123", "가가가가가가가가가가가가가가가가가가가가가가가가가"})
    void invalidNewPasswordDoesNotChangeAnything(String password) throws Exception {
        change(CURRENT, password, password).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"currentPassword", "newPassword", "newPasswordConfirm"})
    void rejectsMissingAndNullFields(String field) throws Exception {
        var input = new HashMap<String, String>(body(CURRENT, NEXT, NEXT));
        input.remove(field);
        send(mapper.writeValueAsString(input)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        input.put(field, null);
        send(mapper.writeValueAsString(input)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}"})
    void rejectsMalformedOrEmptyBody(String input) throws Exception {
        send(input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertUnchanged();
    }

    @Test
    void rejectsMismatchAfterCheckingCurrentPassword() throws Exception {
        change("wrong-password", NEXT, "different").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INVALID"));
        change(CURRENT, NEXT, "different").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_MISMATCH"));
        change("가".repeat(25), NEXT, NEXT).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INVALID"));
        assertUnchanged();
    }

    @Test
    void missingAndWithdrawnUsersAreNotFound() throws Exception {
        access = accessTokens.issue(UUID.randomUUID());
        change(CURRENT, NEXT, NEXT).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        access = accessTokens.issue(user.getId());
        jdbc.update("UPDATE users SET account_status = 'WITHDRAWN' WHERE id = ?", user.getId());
        change(CURRENT, NEXT, NEXT).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertUnchanged();
    }

    @Test
    void bearerAuthenticationIsRequiredWithoutCsrf() throws Exception {
        mvc.perform(patch("/api/users/me/password").contentType("application/json")
                        .content(mapper.writeValueAsString(body(CURRENT, NEXT, NEXT))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        access = "broken-token";
        change(CURRENT, NEXT, NEXT).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCESS_INVALID"));
        assertUnchanged();
    }

    @Test
    void expiredBearerTokenIsRejected() throws Exception {
        var now = java.time.Instant.now();
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer("vector-act-test").subject(user.getId().toString())
                .issuedAt(now.minusSeconds(1000)).expiresAt(now.minusSeconds(100))
                .claim("token_use", "access").build();
        var encoder = org.springframework.security.oauth2.jwt.NimbusJwtEncoder.withSecretKey(
                new javax.crypto.spec.SecretKeySpec(SIGNING_KEY, "HmacSHA256")).build();
        access = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();
        change(CURRENT, NEXT, NEXT).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCESS_EXPIRED"));
        assertUnchanged();
    }

    @Test
    void emptyCurrentPasswordAndConfirmationAreValidationErrors() throws Exception {
        change("", NEXT, NEXT).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        change(CURRENT, NEXT, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertUnchanged();
    }

    @Test
    void succeedsWithoutAnyRefreshTokens() throws Exception {
        tokens.deleteAll();
        change(CURRENT, NEXT, NEXT).andExpect(status().isOk());
        assertThat(tokens.count()).isZero();
        assertThat(passwords.matches(NEXT, users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void validationNeverReturnsOrPrintsPasswords() throws Exception {
        String shortPassword = "secret";
        String response = change(CURRENT, shortPassword, "confirmation-secret")
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(CURRENT, shortPassword, "confirmation-secret");
        assertThat(new ChangePasswordRequest(CURRENT, NEXT, NEXT).toString()).doesNotContain(CURRENT, NEXT);
    }

    @Test
    void rateLimitsCurrentPasswordGuessingPerUser() throws Exception {
        for (int i = 0; i < 10; i++) change("wrong-password", NEXT, NEXT).andExpect(status().isForbidden());
        change(CURRENT, NEXT, NEXT).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"users", "refresh_tokens"})
    void storageFailureRollsBackPasswordAndAllRevocations(String table) throws Exception {
        token(user);
        String condition = table.equals("users") ? "password_hash = '" + user.getPasswordHash() + "'" : "revoked_at IS NULL";
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT a08_failure CHECK (" + condition + ")");
        try {
            change(CURRENT, NEXT, NEXT).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
            assertUnchanged();
            assertThat(tokens.findByUserIdAndRevokedAtIsNull(user.getId())).hasSize(2);
        } finally {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT a08_failure");
        }
    }

    @Test
    void unavailableDatabaseReturns503WithoutClearingCookie() throws Exception {
        doThrow(new org.springframework.transaction.CannotCreateTransactionException("database unavailable"))
                .when(service).changePassword(user.getId(), CURRENT, NEXT, NEXT);
        change(CURRENT, NEXT, NEXT).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertUnchanged();
    }

    @Test
    void concurrentRefreshCannotLeaveAnActiveToken() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var change = executor.submit(() -> { start.await(); return change(CURRENT, NEXT, NEXT).andReturn().getResponse().getStatus(); });
            var refresh = executor.submit(() -> { start.await(); return refresh().andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(change.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(refresh.get(20, TimeUnit.SECONDS)).isIn(200, 401);
        }
        assertThat(tokens.findByUserIdAndRevokedAtIsNull(user.getId())).isEmpty();
    }

    @Test
    void concurrentOldPasswordLoginCannotLeaveAnActiveToken() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var change = executor.submit(() -> { start.await(); return change(CURRENT, NEXT, NEXT).andReturn().getResponse().getStatus(); });
            var login = executor.submit(() -> { start.await(); return login(CURRENT).andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(change.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(login.get(20, TimeUnit.SECONDS)).isIn(200, 401);
        }
        assertThat(tokens.findByUserIdAndRevokedAtIsNull(user.getId())).isEmpty();
    }

    @Test
    void concurrentChangesCannotBothUseTheOldPassword() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> request = () -> {
                start.await();
                return change(CURRENT, NEXT, NEXT).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 403);
        }
        assertThat(tokens.findByUserIdAndRevokedAtIsNull(user.getId())).isEmpty();
    }

    private RefreshToken token(User owner) {
        return tokens.saveAndFlush(RefreshToken.create(owner, generator.hash(generator.generate()), OffsetDateTime.now()));
    }
    private Map<String, String> body(String current, String next, String confirm) {
        return Map.of("currentPassword", current, "newPassword", next, "newPasswordConfirm", confirm);
    }
    private ResultActions change(String current, String next, String confirm) throws Exception {
        return send(mapper.writeValueAsString(body(current, next, confirm)));
    }
    private ResultActions send(String body) throws Exception {
        return mvc.perform(patch("/api/users/me/password").header("Authorization", "Bearer " + access)
                .contentType("application/json").content(body));
    }
    private ResultActions refresh() throws Exception {
        return mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(new Cookie("refreshToken", rawRefresh)));
    }
    private ResultActions login(String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("email", user.getEmail(), "password", password))));
    }
    private void assertUnchanged() {
        var saved = users.findById(user.getId()).orElseThrow();
        assertThat(saved.getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(saved.getUpdatedAt()).isEqualTo(OLD_TIME);
        assertThat(tokens.findById(original.getId()).orElseThrow().getRevokedAt()).isNull();
    }
}
