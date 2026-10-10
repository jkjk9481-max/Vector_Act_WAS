package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.member.business.*;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.EmailChangeRequest;
import com.personalab.vectoract.vector_act_was.global.auth.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emailchange;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false"})
@AutoConfigureMockMvc
class EmailChangeRequestIntegrationTests {
    private static final String PATH = "/api/users/me/email-change-requests";
    private static final String PASSWORD = "example-password-2026";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AuthOneTimeTokenRepository tokens;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired PasswordEncoder passwords;
    @Autowired EmailChangeRequestService service;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean RefreshTokenGenerator generator;
    @MockitoBean EmailChangeDelivery delivery;
    private User user;
    private String bearer;
    private String newEmail;

    @BeforeEach
    void prepare() {
        tokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", passwords.encode(PASSWORD), "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
        newEmail = UUID.randomUUID() + "@example.com";
    }

    @Test
    void issuesHashOnlyFifteenMinuteTokenToNewEmailWithoutChangingAccountOrLogin() throws Exception {
        var refresh = refreshTokens.saveAndFlush(RefreshToken.create(user, generator.hash(generator.generate()), OffsetDateTime.now()));
        doAnswer(invocation -> {
            EmailChangeRequested event = invocation.getArgument(0);
            assertThat(tokens.findByTokenHash(generator.hash(event.rawToken()))).isPresent();
            return null;
        }).when(delivery).send(any());
        String response = send(" " + newEmail.toUpperCase(Locale.ROOT) + " ", PASSWORD)
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn().getResponse().getContentAsString();
        var captor = ArgumentCaptor.forClass(EmailChangeRequested.class);
        verify(delivery).send(captor.capture());
        var event = captor.getValue();
        var token = tokens.findByTokenHash(generator.hash(event.rawToken())).orElseThrow();
        assertThat(event.newEmail()).isEqualTo(newEmail);
        assertThat(event.rawToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(token.getUser().getId()).isEqualTo(user.getId());
        assertThat(token.getTokenType()).isEqualTo(AuthOneTimeToken.TokenType.EMAIL_CHANGE);
        assertThat(token.getNewEmail()).isEqualTo(newEmail);
        assertThat(token.getTokenHash()).hasSize(64).isNotEqualTo(event.rawToken());
        assertThat(token.getUsedAt()).isNull();
        assertThat(Duration.between(token.getCreatedAt(), token.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(event.expiresAt().toInstant()).isCloseTo(token.getExpiresAt().toInstant(), within(1, java.time.temporal.ChronoUnit.MILLIS));
        assertUnchanged();
        assertThat(refreshTokens.findById(refresh.getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(response).doesNotContain(newEmail, PASSWORD, event.rawToken(), token.getTokenHash());
        assertThat(event.toString()).doesNotContain(newEmail, event.rawToken());
        assertThat(new EmailChangeRequest(newEmail, PASSWORD).toString()).doesNotContain(newEmail, PASSWORD);
    }

    @Test
    void reissueInvalidatesOnlyThisUsersPreviousEmailChangeTokens() throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var reauth = tokens.saveAndFlush(AuthOneTimeToken.createReauth(user, generator.hash(generator.generate()), now));
        var reset = tokens.saveAndFlush(AuthOneTimeToken.createPasswordReset(user, generator.hash(generator.generate()), now));
        var other = users.saveAndFlush(User.create("other@example.com", "hash", "다른 회원"));
        var othersToken = tokens.saveAndFlush(AuthOneTimeToken.createEmailChange(other, generator.hash(generator.generate()), "other-new@example.com", now));
        send(newEmail, PASSWORD).andExpect(status().isAccepted());
        var previous = tokens.findAll().stream().filter(t -> newEmail.equals(t.getNewEmail())).findFirst().orElseThrow();
        send("second@example.com", PASSWORD).andExpect(status().isAccepted());
        var invalidatedAt = tokens.findById(previous.getId()).orElseThrow().getUsedAt();
        assertThat(invalidatedAt).isNotNull();
        send("third@example.com", PASSWORD).andExpect(status().isAccepted());
        assertThat(tokens.findById(previous.getId()).orElseThrow().getUsedAt()).isEqualTo(invalidatedAt);
        assertThat(tokens.findById(reauth.getId()).orElseThrow().getUsedAt()).isNull();
        assertThat(tokens.findById(reset.getId()).orElseThrow().getUsedAt()).isNull();
        assertThat(tokens.findById(othersToken.getId()).orElseThrow().getUsedAt()).isNull();
        assertThat(tokens.findAll().stream().filter(t -> t.getUser().getId().equals(user.getId())
                && t.getTokenType() == AuthOneTimeToken.TokenType.EMAIL_CHANGE && t.getUsedAt() == null)).hasSize(1);
        assertUnchanged();
    }

    @Test
    void incorrectPasswordDoesNotInvalidatePreviousToken() throws Exception {
        send(newEmail, PASSWORD).andExpect(status().isAccepted());
        send("another@example.com", "wrong").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INVALID"));
        send("another@example.com", "가".repeat(25)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INVALID"));
        assertThat(tokens.findAll()).singleElement().satisfies(t -> assertThat(t.getUsedAt()).isNull());
        verify(delivery, times(1)).send(any());
    }

    @Test
    void currentEmailIsValidationErrorAndExistingEmailIsConflictIncludingWithdrawnOwner() throws Exception {
        send(user.getEmail().toUpperCase(Locale.ROOT), PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        var other = users.saveAndFlush(User.create(newEmail, "hash", "다른 회원"));
        send(newEmail, PASSWORD).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));
        other.withdraw(OffsetDateTime.now());
        users.saveAndFlush(other);
        send(newEmail, PASSWORD).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));
        assertThat(tokens.count()).isZero();
        verifyNoInteractions(delivery);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "", "{\"newEmail\":null,\"currentPassword\":\"password\"}",
            "{\"newEmail\":\"\",\"currentPassword\":\"password\"}",
            "{\"newEmail\":\"not-email\",\"currentPassword\":\"password\"}",
            "{\"newEmail\":\"new@example.com\"}",
            "{\"newEmail\":\"new@example.com\",\"currentPassword\":\"\"}"})
    void rejectsInvalidInput(String json) throws Exception {
        mvc.perform(post(PATH).header("Authorization", bearer).contentType("application/json").content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(tokens.count()).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void rejectsOverlongEmail() throws Exception {
        send("a".repeat(250) + "@example.com", PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void requiresBearerAndDoesNotRequireCsrf() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(body(newEmail, PASSWORD)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        mvc.perform(post(PATH).header("Authorization", "Bearer invalid").contentType("application/json")
                        .content(body(newEmail, PASSWORD)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("ACCESS_INVALID"));
        mvc.perform(post(PATH).header("Authorization", bearer).header("X-CSRF-TOKEN", "invalid")
                        .contentType("application/json").content(body(newEmail, PASSWORD)))
                .andExpect(status().isAccepted());
        verify(delivery).send(any());
    }

    @Test
    void withdrawnAccountIsRejected() throws Exception {
        jdbc.update("UPDATE users SET account_status = 'WITHDRAWN' WHERE id = ?", user.getId());
        send(newEmail, PASSWORD).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertThat(tokens.count()).isZero();
    }

    @Test
    void rateLimitCountsFailedAttemptsPerMemberAndIsSeparateFromA11() throws Exception {
        for (int i = 0; i < 10; i++) send(newEmail, "wrong").andExpect(status().isForbidden());
        send(newEmail, PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        var other = users.saveAndFlush(User.create("independent@example.com", passwords.encode(PASSWORD), "회원"));
        bearer = "Bearer " + accessTokens.issue(other.getId());
        send(newEmail, PASSWORD).andExpect(status().isAccepted());
        mvc.perform(post("/api/auth/password-reset-requests")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json").content("{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void insertFailureRollsBackPreviousTokenInvalidationAndDoesNotDeliver() throws Exception {
        send(newEmail, PASSWORD).andExpect(status().isAccepted());
        clearInvocations(delivery);
        jdbc.execute("ALTER TABLE auth_one_time_tokens ADD CONSTRAINT a13_fail CHECK (new_email <> 'fail@example.com')");
        try {
            send("fail@example.com", PASSWORD).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
            assertThat(tokens.findAll()).singleElement().satisfies(t -> assertThat(t.getUsedAt()).isNull());
            verifyNoInteractions(delivery);
            assertUnchanged();
        } finally {
            jdbc.execute("ALTER TABLE auth_one_time_tokens DROP CONSTRAINT a13_fail");
        }
    }

    @Test
    void generationFailureDoesNotInvalidateExistingToken() throws Exception {
        send(newEmail, PASSWORD).andExpect(status().isAccepted());
        clearInvocations(delivery);
        doThrow(new IllegalStateException("random unavailable")).when(generator).generate();
        send("another@example.com", PASSWORD).andExpect(status().isInternalServerError());
        assertThat(tokens.findAll()).singleElement().satisfies(t -> assertThat(t.getUsedAt()).isNull());
        verifyNoInteractions(delivery);
    }

    @Test
    void rollbackAfterEventPreventsDelivery() {
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.request(user.getId(), newEmail, PASSWORD);
            throw new IllegalStateException("failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tokens.count()).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void simultaneousReissuesLeaveOnlyOneUnusedToken() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) {
                final String target = "concurrent" + i + "@example.com";
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    service.request(user.getId(), target, PASSWORD);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        assertThat(tokens.findAll()).hasSize(2);
        assertThat(tokens.findAll().stream().filter(t -> t.getUsedAt() == null)).hasSize(1);
        verify(delivery, times(2)).send(any());
    }

    @Test
    void emailChangeTokenCannotAuthorizeWithdrawalAndIsSingleUseBeforeExpiry() throws Exception {
        send(newEmail, PASSWORD).andExpect(status().isAccepted());
        var captor = ArgumentCaptor.forClass(EmailChangeRequested.class);
        verify(delivery).send(captor.capture());
        var event = captor.getValue();
        mvc.perform(delete("/api/users/me").header("Authorization", bearer).header("X-Reauth-Token", event.rawToken()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("REAUTH_REQUIRED"));
        var token = tokens.findByTokenHash(generator.hash(event.rawToken())).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(tokens.consumeIfUsable(token.getTokenHash(), user.getId(), AuthOneTimeToken.TokenType.EMAIL_CHANGE,
                    token.getExpiresAt())).isZero();
            assertThat(tokens.consumeIfUsable(token.getTokenHash(), user.getId(), AuthOneTimeToken.TokenType.EMAIL_CHANGE,
                    token.getCreatedAt())).isOne();
            assertThat(tokens.consumeIfUsable(token.getTokenHash(), user.getId(), AuthOneTimeToken.TokenType.EMAIL_CHANGE,
                    token.getCreatedAt())).isZero();
        });
        assertUnchanged();
    }

    @Test
    void deliveryFailureKeepsAcceptedTokenAndOriginalEmail() throws Exception {
        doThrow(new IllegalStateException("mail failed")).when(delivery).send(any());
        send(newEmail, PASSWORD).andExpect(status().isAccepted());
        assertThat(tokens.findAll()).singleElement().satisfies(t -> assertThat(t.getUsedAt()).isNull());
        assertUnchanged();
    }

    private void assertUnchanged() {
        var stored = users.findById(user.getId()).orElseThrow();
        assertThat(stored.getEmail()).isEqualTo(user.getEmail());
        assertThat(stored.getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(stored.getName()).isEqualTo(user.getName());
    }

    private ResultActions send(String email, String password) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", bearer)
                .contentType("application/json").content(body(email, password)));
    }

    private String body(String email, String password) {
        return "{\"newEmail\":\"" + email + "\",\"currentPassword\":\"" + password + "\"}";
    }
}
