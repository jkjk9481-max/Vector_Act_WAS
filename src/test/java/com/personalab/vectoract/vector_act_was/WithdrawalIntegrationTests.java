package com.personalab.vectoract.vector_act_was;

import com.jayway.jsonpath.JsonPath;
import com.personalab.vectoract.vector_act_was.domain.member.business.*;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.*;
import com.personalab.vectoract.vector_act_was.global.error.*;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 실제 HTTP 인증 필터, 서비스 트랜잭션, H2 DB를 연결합니다.
 * 외부 저장소만 모의 객체로 대체해 실패와 재시도를 재현합니다.
 * 테스트에 @Transactional을 붙이지 않아 실제 커밋/롤백 결과를 확인합니다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:withdrawal;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-initial-delay-ms=86400000"})
@AutoConfigureMockMvc
class WithdrawalIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository tokens;
    @Autowired UserConsentRepository consents;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired RefreshTokenGenerator generator;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberPurgeService purge;
    @Autowired MemberPurgeScheduler scheduler;
    @MockitoSpyBean WithdrawalService service;
    @MockitoBean MemberDataEraser eraser;
    private User user;
    private String access;
    private String reauth;
    private String refresh;

    @BeforeEach
    void prepare() {
        consents.deleteAll();
        tokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create("withdraw@example.com", "unused-password-hash", "회원"));
        access = accessTokens.issue(user.getId());
        reauth = generator.generate();
        refresh = generator.generate();
        tokens.saveAndFlush(AuthOneTimeToken.createReauth(user, generator.hash(reauth), OffsetDateTime.now()));
        refreshTokens.saveAndFlush(RefreshToken.create(user, generator.hash(refresh), OffsetDateTime.now()));
        consents.saveAndFlush(UserConsent.create(user, UserConsent.ConsentType.TERMS, "v1"));
    }

    @Test
    void acceptsWithdrawalAndImmediatelyBlocksTokensButRetainsData() throws Exception {
        String extra = generator.generate();
        tokens.saveAndFlush(AuthOneTimeToken.createReauth(user, generator.hash(extra), OffsetDateTime.now()));
        var response = request(reauth).andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().maxAge("refreshToken", 0))
                .andReturn().getResponse().getContentAsString();
        Map<String, String> data = JsonPath.read(response, "$.data");
        assertThat(data).containsOnlyKeys("deletedAt", "purgeAt");
        assertThat(Duration.between(OffsetDateTime.parse(data.get("deletedAt")),
                OffsetDateTime.parse(data.get("purgeAt")))).isEqualTo(Duration.ofDays(7));
        var saved = users.findById(user.getId()).orElseThrow();
        assertThat(saved.getAccountStatus()).isEqualTo(User.AccountStatus.WITHDRAWN);
        assertThat(saved.getDeletedAt()).isNotNull();
        assertThat(Duration.between(saved.getDeletedAt(), saved.getPurgeAt())).isEqualTo(Duration.ofDays(7));
        assertThat(saved.getEmail()).isEqualTo(user.getEmail());
        assertThat(consents.count()).isEqualTo(1);
        assertThat(refreshTokens.findAll()).allSatisfy(t -> assertThat(t.getRevokedAt()).isNotNull());
        assertThat(tokens.findAll()).allSatisfy(t -> assertThat(t.getUsedAt()).isNotNull());
        verifyNoInteractions(eraser);
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + access)
                        .contentType("application/json").content("{\"name\":\"changed\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/reauth").header("Authorization", "Bearer " + access)
                        .contentType("application/json").content("{\"password\":\"anything\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(new Cookie("refreshToken", refresh)))
                .andExpect(status().isUnauthorized());
        request(reauth).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_DELETED"));
        assertThat(users.findById(user.getId()).orElseThrow().getPurgeAt()).isEqualTo(saved.getPurgeAt());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"bad", " ", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void rejectsMissingMalformedAndUnknownReauth(String token) throws Exception {
        request(token).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("REAUTH_REQUIRED"));
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "used", "wrongType", "otherOwner"})
    void rejectsUnusableReauth(String reason) throws Exception {
        switch (reason) {
            case "expired" -> jdbc.update("UPDATE auth_one_time_tokens SET expires_at = ?",
                    OffsetDateTime.now().minusSeconds(1));
            case "used" -> jdbc.update("UPDATE auth_one_time_tokens SET used_at = ?", OffsetDateTime.now());
            case "wrongType" -> jdbc.update("UPDATE auth_one_time_tokens SET token_type = 'PASSWORD_RESET'");
            case "otherOwner" -> {
                var other = users.saveAndFlush(User.create("other@example.com", "unused", "다른회원"));
                jdbc.update("UPDATE auth_one_time_tokens SET user_id = ?", other.getId());
            }
        }
        request(reauth).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("REAUTH_REQUIRED"));
        assertThat(users.findById(user.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(refreshTokens.findAll().getFirst().getRevokedAt()).isNull();
    }

    @Test
    void rejectsBodyAndMissingInvalidOrUnknownBearer() throws Exception {
        mvc.perform(delete("/api/users/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        mvc.perform(delete("/api/users/me").header("Authorization", "Bearer bad"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("ACCESS_INVALID"));
        mvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + access)
                        .header("X-Reauth-Token", reauth).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + accessTokens.issue(UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertUnchanged();
    }

    @Test
    void rateLimitsReauthGuessing() throws Exception {
        for (int i = 0; i < 10; i++) request("bad").andExpect(status().isForbidden());
        request(reauth).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertUnchanged();
    }

    @Test
    void databaseFailureRollsBackWithdrawalAndConsumption() throws Exception {
        // 실제 UPDATE를 거절하는 제약 조건으로 토큰 소비까지 함께 롤백되는지 확인합니다.
        jdbc.execute("ALTER TABLE users ADD CONSTRAINT a10_fail CHECK (account_status = 'ACTIVE')");
        try {
            request(reauth).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
            assertUnchanged();
        } finally {
            jdbc.execute("ALTER TABLE users DROP CONSTRAINT a10_fail");
        }
        request(reauth).andExpect(status().isAccepted());
    }

    @Test
    void databaseUnavailableReturns503() throws Exception {
        doThrow(new org.springframework.transaction.CannotCreateTransactionException("unavailable"))
                .when(service).withdraw(user.getId(), reauth);
        request(reauth).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
        assertUnchanged();
    }

    @Test
    void concurrentWithdrawalSucceedsExactlyOnce() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> attempt = () -> {
                start.await();
                try { service.withdraw(user.getId(), reauth); return "ACCEPTED"; }
                catch (BusinessException e) { return e.getErrorCode().name(); }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ACCEPTED", "ACCOUNT_DELETED");
        }
    }

    @Test
    void preservesDataBeforeDeadlineThenDeletesChildrenAndUser() throws Exception {
        request(reauth).andExpect(status().isAccepted());
        assertThat(purge.purge(user.getId())).isFalse();
        verifyNoInteractions(eraser);
        makeDue();
        // 저장소 콜백이 실행될 때 DB 회원/토큰/동의 정보가 아직 남아 있어야 합니다.
        doAnswer(invocation -> {
            assertThat(users.existsById(user.getId())).isTrue();
            assertThat(tokens.count()).isEqualTo(1);
            assertThat(consents.count()).isEqualTo(1);
            return null;
        }).when(eraser).deleteExternalData(user.getId());
        scheduler.purgeDueUsers();
        var order = inOrder(eraser);
        order.verify(eraser).deleteExternalData(user.getId());
        order.verify(eraser).deleteDatabaseData(user.getId());
        assertThat(users.count()).isZero();
        assertThat(tokens.count()).isZero();
        assertThat(refreshTokens.count()).isZero();
        assertThat(consents.count()).isZero();
        assertThat(purge.purge(user.getId())).isFalse();
    }

    @Test
    void storageFailurePreservesDatabaseAndNextRunRetries() throws Exception {
        request(reauth).andExpect(status().isAccepted());
        makeDue();
        doThrow(new IllegalStateException("storage unavailable")).doNothing()
                .when(eraser).deleteExternalData(user.getId());
        scheduler.purgeDueUsers();
        assertThat(users.findById(user.getId()).orElseThrow().getAccountStatus()).isEqualTo(User.AccountStatus.WITHDRAWN);
        assertThat(tokens.count()).isEqualTo(1);
        assertThat(refreshTokens.count()).isEqualTo(1);
        assertThat(consents.count()).isEqualTo(1);
        verify(eraser, never()).deleteDatabaseData(any());
        scheduler.purgeDueUsers();
        assertThat(users.existsById(user.getId())).isFalse();
        verify(eraser, times(2)).deleteExternalData(user.getId());
    }

    @Test
    void databaseDeleteFailureRollsBackAndRetriesExternalDeletion() throws Exception {
        request(reauth).andExpect(status().isAccepted());
        makeDue();
        // 회원을 참조하는 예상치 못한 자식 테이블로 최종 DELETE 실패를 재현합니다.
        jdbc.execute("CREATE TABLE a10_blocker (user_id UUID REFERENCES users(id))");
        jdbc.update("INSERT INTO a10_blocker VALUES (?)", user.getId());
        try {
            scheduler.purgeDueUsers();
            assertThat(users.existsById(user.getId())).isTrue();
            assertThat(tokens.count()).isEqualTo(1);
            assertThat(refreshTokens.count()).isEqualTo(1);
            assertThat(consents.count()).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TABLE a10_blocker");
        }
        scheduler.purgeDueUsers();
        assertThat(users.existsById(user.getId())).isFalse();
        verify(eraser, times(2)).deleteExternalData(user.getId());
    }

    @Test
    void activeAndNotYetDueUsersAreExcluded() {
        assertThat(users.findPurgeCandidates(OffsetDateTime.now().plusDays(30))).isEmpty();
        assertThat(purge.purge(user.getId())).isFalse();
        verifyNoInteractions(eraser);
    }

    @Test
    void schedulerIncludesExactDeadlineAndExcludesFutureDeadline() throws Exception {
        request(reauth).andExpect(status().isAccepted());
        var deadline = users.findById(user.getId()).orElseThrow().getPurgeAt();
        assertThat(users.findPurgeCandidates(deadline.minusSeconds(1))).isEmpty();
        assertThat(users.findPurgeCandidates(deadline)).containsExactly(user.getId());
    }

    private ResultActions request(String token) throws Exception {
        var request = delete("/api/users/me").header("Authorization", "Bearer " + access);
        if (token != null) request.header("X-Reauth-Token", token);
        return mvc.perform(request);
    }
    private void makeDue() {
        jdbc.update("UPDATE users SET deleted_at = ?, purge_at = ? WHERE id = ?",
                OffsetDateTime.now().minusDays(8), OffsetDateTime.now().minusDays(1), user.getId());
    }
    private void assertUnchanged() {
        var saved = users.findById(user.getId()).orElseThrow();
        assertThat(saved.getAccountStatus()).isEqualTo(User.AccountStatus.ACTIVE);
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getPurgeAt()).isNull();
        assertThat(tokens.findByTokenHash(generator.hash(reauth)).orElseThrow().getUsedAt()).isNull();
        assertThat(refreshTokens.findAll().getFirst().getRevokedAt()).isNull();
    }
}
