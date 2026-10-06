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

/**
 * A08 통합 테스트: 실제 Spring 설정·인증 필터·Controller·Service·Repository를 연결해 확인합니다.
 * MockMvc는 실제 네트워크 포트를 열지 않고 HTTP 요청 처리 과정을 실행합니다.
 * H2는 테스트 전용 메모리 DB입니다. PostgreSQL 호환 모드를 쓰지만 운영 DB 그 자체는 아닙니다.
 * 테스트에 @Transactional을 붙이지 않아 API가 실제로 커밋한 결과를 다시 조회하여 확인합니다.
 * 읽는 순서: prepare()로 데이터 준비 → change()/send()로 요청 → 응답 검사 → DB 결과 검사.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:change-password;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
class ChangePasswordIntegrationTests {
    // 만료 JWT를 직접 만들 때 인증 서버와 같은 테스트용 키를 써야 '서명 오류'가 아닌 '만료'를 검사합니다.
    // 클래스당 한 번 생성한 난수 키를 아래 DynamicPropertySource로 Spring에도 전달합니다.
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
    // 보통은 실제 Service를 실행합니다. DB 연결 실패 테스트에서만 지정 호출이 예외를 던지도록 바꿉니다.
    // 테스트가 끝나면 Spring의 Mockito 지원이 설정을 초기화하여 다른 테스트에 영향을 주지 않습니다.
    @MockitoSpyBean ChangePasswordService service;
    private User user;
    private String access;
    private String rawRefresh;
    private RefreshToken original;
    private static final String CURRENT = "CurrentPassword!1";
    private static final String NEXT = "NewPassword!2";
    private static final OffsetDateTime OLD_TIME = OffsetDateTime.parse("2020-01-01T00:00:00Z");

    // 각 테스트 전에 DB를 비우고 독립적인 회원·토큰을 만듭니다. 실행 순서에 의존하지 않게 합니다.
    // updated_at은 과거로 고정해 성공 시 갱신, 실패 시 보존을 기다림 없이 확인합니다.
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
        // 다른 회원, 같은 회원의 두 번째 기기, 이미 폐기된 토큰을 함께 만들어 변경 범위를 비교합니다.
        var other = users.saveAndFlush(User.create("other@example.com", passwords.encode(CURRENT), "다른회원"));
        var otherToken = token(other);
        var deviceTwo = token(user);
        var alreadyRevoked = token(user);
        jdbc.update("UPDATE refresh_tokens SET revoked_at = ?, replaced_by_token_id = ? WHERE id = ?",
                OLD_TIME, deviceTwo.getId(), alreadyRevoked.getId());
        var body = new HashMap<String, String>(body(CURRENT, NEXT, NEXT));
        body.put("userId", other.getId().toString());
        // 다른 회원 ID를 쿼리/본문에 넣어도 Bearer 토큰 소유자만 변경되는지 검사합니다.
        // andExpect는 예상한 응답인지 단언하고, andReturn은 응답 객체를 꺼내 추가 검사할 때 씁니다.
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

    // @ParameterizedTest는 @ValueSource의 각 값으로 같은 테스트를 반복합니다. 최소/최대 경계를 확인합니다.
    @ParameterizedTest
    @ValueSource(ints = {8, 32})
    void acceptsPasswordLengthBoundaries(int length) throws Exception {
        String password = "a".repeat(length);
        change(CURRENT, password, password).andExpect(status().isOk());
        assertThat(passwords.matches(password, users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void accepts72Utf8BytesAndPreservesWhitespace() throws Exception {
        // 한글 23자(69바이트) + 공백 3개(3바이트) = 정확히 72바이트입니다.
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
        // JSON에 키가 없는 경우와 키는 있지만 값이 null인 경우를 각각 검사합니다.
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
        // 두 비밀번호 조건이 모두 틀리면 현재 비밀번호 오류를 먼저 반환해야 합니다.
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
        // 이미 만료된 시각으로 JWT를 서명합니다. 실제 만료를 기다리지 않고 401 코드 분류를 검증합니다.
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
        // 같은 회원의 10회 실패 이후에는 올바른 비밀번호여도 11번째 요청이 429로 제한되어야 합니다.
        for (int i = 0; i < 10; i++) change("wrong-password", NEXT, NEXT).andExpect(status().isForbidden());
        change(CURRENT, NEXT, NEXT).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"users", "refresh_tokens"})
    void storageFailureRollsBackPasswordAndAllRevocations(String table) throws Exception {
        // 임시 CHECK 제약으로 실제 DB UPDATE를 실패시킵니다. 응답뿐 아니라 트랜잭션 롤백까지 확인합니다.
        // users와 refresh_tokens 각각의 저장 실패를 시험하고 finally에서 제약을 반드시 제거합니다.
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
        // Service 호출에 DB 연결 실패 예외를 주입해 503 매핑과 쿠키 보존을 확인합니다.
        doThrow(new org.springframework.transaction.CannotCreateTransactionException("database unavailable"))
                .when(service).changePassword(user.getId(), CURRENT, NEXT, NEXT);
        change(CURRENT, NEXT, NEXT).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertUnchanged();
    }

    @Test
    void concurrentRefreshCannotLeaveAnActiveToken() throws Exception {
        // CountDownLatch는 두 작업을 같은 출발 신호에서 시작시키고, executor는 별도 스레드에서 실행합니다.
        // refresh가 먼저면 200, 변경이 먼저면 401이 가능하지만 최종 미폐기 토큰은 항상 없어야 합니다.
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
        // 이전 비밀번호 로그인과 변경이 경합해도 변경 이후 활성 Refresh Token이 남지 않아야 합니다.
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
        // 같은 이전 비밀번호를 보낸 두 요청 중 하나만 성공해야 합니다. 잠금 이후 다시 읽은 해시로 검증합니다.
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

    // 아래 메서드들은 반복되는 준비·요청·검증 코드를 모은 테스트 보조 함수입니다.
    // 임의의 새 토큰은 각 로그인 기기를 나타내며 DB에는 원문 대신 해시만 저장합니다.
    private RefreshToken token(User owner) {
        return tokens.saveAndFlush(RefreshToken.create(owner, generator.hash(generator.generate()), OffsetDateTime.now()));
    }
    private Map<String, String> body(String current, String next, String confirm) {
        return Map.of("currentPassword", current, "newPassword", next, "newPasswordConfirm", confirm);
    }
    private ResultActions change(String current, String next, String confirm) throws Exception {
        return send(mapper.writeValueAsString(body(current, next, confirm)));
    }
    // CSRF 토큰 없이 Bearer 헤더만 보내 A08 인증 명세를 그대로 확인합니다.
    private ResultActions send(String body) throws Exception {
        return mvc.perform(patch("/api/users/me/password").header("Authorization", "Bearer " + access)
                .contentType("application/json").content(body));
    }
    // 기존 쿠키 기반 API에는 CSRF가 필요하므로 테스트용 csrf()를 함께 넣습니다.
    private ResultActions refresh() throws Exception {
        return mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(new Cookie("refreshToken", rawRefresh)));
    }
    private ResultActions login(String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("email", user.getEmail(), "password", password))));
    }
    // 오류 응답만 맞아도 DB가 바뀌었다면 실패입니다. 해시·수정 시각·토큰 폐기 상태를 다시 조회합니다.
    private void assertUnchanged() {
        var saved = users.findById(user.getId()).orElseThrow();
        assertThat(saved.getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(saved.getUpdatedAt()).isEqualTo(OLD_TIME);
        assertThat(tokens.findById(original.getId()).orElseThrow().getRevokedAt()).isNull();
    }
}
