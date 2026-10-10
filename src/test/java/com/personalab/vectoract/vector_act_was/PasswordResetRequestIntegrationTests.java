package com.personalab.vectoract.vector_act_was;

import com.jayway.jsonpath.JsonPath;
import com.personalab.vectoract.vector_act_was.domain.member.business.*;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.PasswordResetRequest;
import com.personalab.vectoract.vector_act_was.global.auth.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CSRF 필터·Controller·Service·Repository와 H2 DB를 실제로 연결합니다.
 * 외부 메일 발송만 모의 객체입니다. SMTP 서버나 실제 수신함에 메일을 보내지 않습니다.
 * 테스트 전체를 트랜잭션으로 감싸지 않아 실제 커밋 후 발송/롤백을 확인합니다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:passwordreset;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false"})
@AutoConfigureMockMvc
class PasswordResetRequestIntegrationTests {
    private static final String PATH = "/api/auth/password-reset-requests";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AuthOneTimeTokenRepository tokens;
    @Autowired RefreshTokenRepository refreshTokens;
    @MockitoSpyBean RefreshTokenGenerator generator;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PasswordResetDelivery delivery;
    @MockitoSpyBean PasswordResetRequestService service;
    private User user;
    private String address;
    private String ip;

    @BeforeEach
    void prepare() {
        tokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        // 요청 제한 카운터가 테스트 사이에 공유되므로 매번 새 이메일과 주소를 사용합니다.
        address = UUID.randomUUID() + "@example.com";
        ip = UUID.randomUUID().toString();
        user = users.saveAndFlush(User.create(address, "unchanged-password-hash", "회원"));
    }

    @Test
    void returnsSameResponseForActiveUnknownAndWithdrawnAccounts() throws Exception {
        var active = send(address).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse().getContentAsString();
        var unknown = send(UUID.randomUUID() + "@example.com").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE users SET account_status = 'WITHDRAWN' WHERE id = ?", user.getId());
        var withdrawn = send(address).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(unknown).isEqualTo(active);
        assertThat(withdrawn).isEqualTo(active);
        Map<String, Object> data = JsonPath.read(active, "$.data");
        assertThat(data).containsOnlyKeys("accepted");
        assertThat(tokens.count()).isEqualTo(1);
        verify(delivery, times(1)).send(any());
        assertThat(active).doesNotContain(address, user.getId().toString(), "token", "expiresAt");
    }

    @Test
    void createsFifteenMinuteHashOnlyTokenAndDeliversAfterCommitWithoutChangingLogin() throws Exception {
        var refresh = refreshTokens.saveAndFlush(RefreshToken.create(user, generator.hash(generator.generate()), OffsetDateTime.now()));
        var reauth = tokens.saveAndFlush(AuthOneTimeToken.createReauth(user, generator.hash(generator.generate()), OffsetDateTime.now()));
        // afterCommit 메일 연결 시점에 실제 INSERT가 끝났고 토큰 조회가 가능한지 확인합니다.
        doAnswer(invocation -> {
            PasswordResetRequested event = invocation.getArgument(0);
            assertThat(tokens.findByTokenHash(generator.hash(event.rawToken()))).isPresent();
            return null;
        }).when(delivery).send(any());
        send("  " + address.toUpperCase(Locale.ROOT) + "  ").andExpect(status().isAccepted());
        var captor = ArgumentCaptor.forClass(PasswordResetRequested.class);
        verify(delivery).send(captor.capture());
        var event = captor.getValue();
        assertThat(event.email()).isEqualTo(address);
        assertThat(event.rawToken()).matches("[A-Za-z0-9_-]{43}");
        var token = tokens.findByTokenHash(generator.hash(event.rawToken())).orElseThrow();
        assertThat(token.getTokenType()).isEqualTo(AuthOneTimeToken.TokenType.PASSWORD_RESET);
        assertThat(token.getTokenHash()).hasSize(64).isNotEqualTo(event.rawToken());
        assertThat(token.getUser().getId()).isEqualTo(user.getId());
        assertThat(token.getUsedAt()).isNull();
        assertThat(Duration.between(token.getCreatedAt(), token.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(users.findById(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(refreshTokens.findById(refresh.getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(tokens.findById(reauth.getId()).orElseThrow().getUsedAt()).isNull();
        assertThat(event.toString()).doesNotContain(address, event.rawToken());
        assertThat(new PasswordResetRequest(address).toString()).doesNotContain(address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"email\":null}", "{\"email\":\"\"}", "{\"email\":\"   \"}",
            "{\"email\":\"not-an-email\"}", "{", "", "null"})
    void rejectsInvalidInput(String body) throws Exception {
        mvc.perform(post(PATH).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(tokens.count()).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void rejectsOverlongEmail() throws Exception {
        send("a".repeat(250) + "@example.com").andExpect(status().isBadRequest());
        assertThat(tokens.count()).isZero();
    }

    @Test
    void requiresRealA01CsrfTokenAndMatchingSession() throws Exception {
        var issued = mvc.perform(get("/api/auth/csrf").with(com.personalab.vectoract.vector_act_was.support.ApplicationCsrf.applicationCsrf())).andExpect(status().isOk()).andReturn();
        String csrfToken = JsonPath.read(issued.getResponse().getContentAsString(), "$.data.csrfToken");
        var session = (MockHttpSession) issued.getRequest().getSession(false);
        for (var request : List.of(post(PATH), post(PATH).session(session),
                post(PATH).session(session).header("X-CSRF-TOKEN", "wrong"),
                post(PATH).session(new MockHttpSession()).header("X-CSRF-TOKEN", csrfToken))) {
            mvc.perform(request.contentType("application/json").content(body(address)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        }
        assertThat(tokens.count()).isZero();
        mvc.perform(post(PATH).session(session).header("X-CSRF-TOKEN", csrfToken)
                        .contentType("application/json").content(body(address)))
                .andExpect(status().isAccepted());
    }

    @Test
    void publicEndpointDoesNotRequireOrValidateBearer() throws Exception {
        mvc.perform(post(PATH).with(csrf()).header("Authorization", "Bearer invalid")
                        .contentType("application/json").content(body(address)))
                .andExpect(status().isAccepted());
        // CSRF 없는 경우에는 Bearer 헤더만으로 우회할 수 없습니다.
        mvc.perform(post(PATH).header("Authorization", "Bearer invalid")
                        .contentType("application/json").content(body(address)))
                .andExpect(status().isForbidden());
    }

    @Test
    void limitsSameNormalizedEmailAcrossDifferentIpsIncludingUnknownEmail() throws Exception {
        String unknown = UUID.randomUUID() + "@example.com";
        for (String target : List.of(address, unknown)) {
            for (int i = 0; i < 3; i++) {
                ip = UUID.randomUUID().toString();
                send(target).andExpect(status().isAccepted());
            }
            ip = UUID.randomUUID().toString();
            send(" " + target.toUpperCase(Locale.ROOT) + " ").andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        }
        assertThat(tokens.count()).isEqualTo(3);
    }

    @Test
    void limitsIpAcrossDifferentEmailsWithoutTrustingForwardedHeader() throws Exception {
        for (int i = 0; i < 10; i++) send(UUID.randomUUID() + "@example.com").andExpect(status().isAccepted());
        mvc.perform(post(PATH).with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; })
                        .header("X-Forwarded-For", "203.0.113.1").contentType("application/json").content(body(address)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertThat(tokens.count()).isZero();
    }

    @Test
    void insertFailureRollsBackAndDoesNotDeliver() throws Exception {
        jdbc.execute("ALTER TABLE auth_one_time_tokens ADD CONSTRAINT a11_fail CHECK (token_type = 'REAUTH')");
        try {
            send(address).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
            assertThat(tokens.count()).isZero();
            verifyNoInteractions(delivery);
        } finally {
            jdbc.execute("ALTER TABLE auth_one_time_tokens DROP CONSTRAINT a11_fail");
        }
    }

    @Test
    void databaseUnavailableReturns503() throws Exception {
        doThrow(new org.springframework.transaction.CannotCreateTransactionException("database unavailable"))
                .when(service).request(address);
        send(address).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
        assertThat(tokens.count()).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void deliveryFailureDoesNotExposeMembershipAndKeepsCommittedToken() throws Exception {
        doThrow(new IllegalStateException("mail failure")).when(delivery).send(any());
        var active = send(address).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var unknown = send(UUID.randomUUID() + "@example.com").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(active).isEqualTo(unknown);
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void repeatedRequestsIssueDistinctTokensAndDoNotConsumeExistingOnes() throws Exception {
        send(address).andExpect(status().isAccepted());
        send(address).andExpect(status().isAccepted());
        assertThat(tokens.findAll()).hasSize(2).allSatisfy(t -> assertThat(t.getUsedAt()).isNull());
        assertThat(tokens.findAll().stream().map(AuthOneTimeToken::getTokenHash).distinct().count()).isEqualTo(2);
    }

    @Test
    void resetTokenCannotAuthenticateOrAuthorizeWithdrawal() throws Exception {
        send(address).andExpect(status().isAccepted());
        var captor = ArgumentCaptor.forClass(PasswordResetRequested.class);
        verify(delivery).send(captor.capture());
        String raw = captor.getValue().rawToken();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + raw))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("ACCESS_INVALID"));
        mvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + accessTokens.issue(user.getId()))
                        .header("X-Reauth-Token", raw))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("REAUTH_REQUIRED"));
        assertThat(tokens.findByTokenHash(generator.hash(raw)).orElseThrow().getUsedAt()).isNull();
        // 실제 A12 엔드포인트나 소비 로직은 만들지 않습니다.
    }

    @Test
    void rollbackAfterEventPublicationPreventsDelivery() {
        // INSERT/이벤트 발행 후에도 커밋 전에 실패할 수 있습니다. 이때 이메일이 나가면 안 됩니다.
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.request(address);
            throw new IllegalStateException("failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tokens.count()).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void tokenGenerationFailureDoesNotSaveOrDeliverOrChangeCredentials() throws Exception {
        doThrow(new IllegalStateException("random source unavailable")).when(generator).generate();
        send(address).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        assertThat(tokens.count()).isZero();
        verifyNoInteractions(delivery);
        assertThat(users.findById(user.getId()).orElseThrow().getPasswordHash())
                .isEqualTo(user.getPasswordHash());
    }

    @Test
    void storedResetTokenCanOnlyBeConsumedOnceBeforeExpiry() throws Exception {
        send(address).andExpect(status().isAccepted());
        var token = tokens.findAll().getFirst();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            assertThat(tokens.consumeIfUsable(token.getTokenHash(), user.getId(),
                    AuthOneTimeToken.TokenType.REAUTH, token.getCreatedAt())).isZero();
            assertThat(tokens.consumeIfUsable(token.getTokenHash(), UUID.randomUUID(),
                    AuthOneTimeToken.TokenType.PASSWORD_RESET, token.getCreatedAt())).isZero();
            assertThat(tokens.consumeIfUsable(token.getTokenHash(), user.getId(),
                    AuthOneTimeToken.TokenType.PASSWORD_RESET, token.getExpiresAt())).isZero();
            assertThat(tokens.consumeIfUsable(token.getTokenHash(), user.getId(),
                    AuthOneTimeToken.TokenType.PASSWORD_RESET, token.getCreatedAt())).isOne();
            assertThat(tokens.consumeIfUsable(token.getTokenHash(), user.getId(),
                    AuthOneTimeToken.TokenType.PASSWORD_RESET, token.getCreatedAt())).isZero();
        });
    }

    private ResultActions send(String email) throws Exception {
        return mvc.perform(post(PATH).with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType("application/json").content(body(email)));
    }
    private String body(String email) { return "{\"email\":\"" + email + "\"}"; }
}
