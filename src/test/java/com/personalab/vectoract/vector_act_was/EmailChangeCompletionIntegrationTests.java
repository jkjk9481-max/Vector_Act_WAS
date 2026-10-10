package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emailchangecompletion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "auth.email-change-completion-rate-limit.max-attempts=1000"})
@AutoConfigureMockMvc
class EmailChangeCompletionIntegrationTests {
    private static final String PATH = "/api/users/me/email-changes";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AuthOneTimeTokenRepository tokens;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired PasswordEncoder passwords;
    @Autowired RefreshTokenGenerator generator;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    private User user;
    private String newEmail;
    private String raw;

    @BeforeEach
    void prepare() {
        tokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", passwords.encode("pw-example-2026"), "회원"));
        newEmail = UUID.randomUUID() + "@example.com";
        raw = issue(user, newEmail, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private String issue(User owner, String email, OffsetDateTime now) {
        String value = generator.generate();
        tokens.saveAndFlush(AuthOneTimeToken.createEmailChange(owner, generator.hash(value), email, now));
        return value;
    }

    private ResultActions send(String token) throws Exception {
        return mvc.perform(post(PATH).with(csrf()).contentType("application/json")
                .content("{\"changeToken\":\"" + token + "\"}"));
    }

    @Test
    void changesEmailConsumesTokenAndRevokesEveryRefreshTokenWithoutBearer() throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var first = refreshTokens.saveAndFlush(RefreshToken.create(user, generator.hash(generator.generate()), now));
        var second = refreshTokens.saveAndFlush(RefreshToken.create(user, generator.hash(generator.generate()), now));
        var other = users.saveAndFlush(User.create("other@example.com", "hash", "다른 회원"));
        var othersRefresh = refreshTokens.saveAndFlush(RefreshToken.create(other, generator.hash(generator.generate()), now));
        String passwordHash = user.getPasswordHash();

        String response = send(raw).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();

        var changed = users.findById(user.getId()).orElseThrow();
        assertThat(changed.getEmail()).isEqualTo(newEmail);
        assertThat(changed.getPasswordHash()).isEqualTo(passwordHash);
        assertThat(changed.getName()).isEqualTo("회원");
        assertThat(tokens.findByTokenHash(generator.hash(raw)).orElseThrow().getUsedAt()).isNotNull();
        assertThat(refreshTokens.findById(first.getId()).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(refreshTokens.findById(second.getId()).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(refreshTokens.findById(othersRefresh.getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(response).doesNotContain(newEmail, raw);
    }

    @Test
    void tokenIsSingleUse() throws Exception {
        send(raw).andExpect(status().isOk());
        send(raw).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EMAIL_CHANGE_TOKEN_INVALID"));
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(newEmail);
    }

    @Test
    void unknownExpiredAndWrongTypeTokensAreInvalidAndChangeNothing() throws Exception {
        String original = user.getEmail();
        send("unknown-token").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EMAIL_CHANGE_TOKEN_INVALID"));
        String expired = issue(user, "expired@example.com", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(16));
        send(expired).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EMAIL_CHANGE_TOKEN_INVALID"));
        String reset = generator.generate();
        tokens.saveAndFlush(AuthOneTimeToken.createPasswordReset(user, generator.hash(reset), OffsetDateTime.now(ZoneOffset.UTC)));
        send(reset).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EMAIL_CHANGE_TOKEN_INVALID"));
        String reauth = generator.generate();
        tokens.saveAndFlush(AuthOneTimeToken.createReauth(user, generator.hash(reauth), OffsetDateTime.now(ZoneOffset.UTC)));
        send(reauth).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EMAIL_CHANGE_TOKEN_INVALID"));
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(original);
    }

    @Test
    void reissuedRequestInvalidatesThePreviousToken() throws Exception {
        // A13 재요청이 이전 토큰을 used_at으로 무효화한 상태를 재현합니다.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status ->
                tokens.invalidateByUserAndType(user.getId(), AuthOneTimeToken.TokenType.EMAIL_CHANGE, OffsetDateTime.now(ZoneOffset.UTC)));
        String latest = issue(user, "latest@example.com", OffsetDateTime.now(ZoneOffset.UTC));
        send(raw).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EMAIL_CHANGE_TOKEN_INVALID"));
        send(latest).andExpect(status().isOk());
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isEqualTo("latest@example.com");
    }

    @Test
    void withdrawnOwnerTokenIsInvalid() throws Exception {
        user.withdraw(OffsetDateTime.now(ZoneOffset.UTC));
        users.saveAndFlush(user);
        send(raw).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EMAIL_CHANGE_TOKEN_INVALID"));
    }

    @Test
    void addressTakenAfterRequestIsConflictAndKeepsTokenUsable() throws Exception {
        var holder = users.saveAndFlush(User.create(newEmail, "hash", "선점 회원"));
        var refresh = refreshTokens.saveAndFlush(RefreshToken.create(user, generator.hash(generator.generate()), OffsetDateTime.now(ZoneOffset.UTC)));
        send(raw).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));
        assertThat(tokens.findByTokenHash(generator.hash(raw)).orElseThrow().getUsedAt()).isNull();
        assertThat(refreshTokens.findById(refresh.getId()).orElseThrow().getRevokedAt()).isNull();
        // 탈퇴한 회원의 주소도 사용 중으로 취급합니다.
        holder.withdraw(OffsetDateTime.now(ZoneOffset.UTC));
        users.saveAndFlush(holder);
        send(raw).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isNotEqualTo(newEmail);
    }

    @Test
    void invalidBodyIsValidationError() throws Exception {
        for (String body : List.of("{}", "{\"changeToken\":\"\"}", "{\"changeToken\":\"  \"}",
                "{\"changeToken\":\"" + "a".repeat(256) + "\"}", "not-json")) {
            mvc.perform(post(PATH).with(csrf()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void requiresCsrfButNotBearer() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content("{\"changeToken\":\"" + raw + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isNotEqualTo(newEmail);
    }

    @Test
    void concurrentUseOfOneTokenSucceedsExactlyOnce() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return send(raw).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var result : results) statuses.add(result.get(10, TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
            assertThat(statuses.stream().filter(s -> s == 400)).hasSize(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(newEmail);
    }
}
