package com.personalab.vectoract.vector_act_was;

import com.jayway.jsonpath.JsonPath;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** csrf() 테스트 도우미를 쓰지 않고 A01에서 실제로 받은 토큰으로 A02/A03을 호출합니다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:csrf;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
class CsrfIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired UserConsentRepository consents;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired com.personalab.vectoract.vector_act_was.global.auth.ExpiringCsrfTokenRepository csrfTokens;
    private static final String SIGNUP = """
            {"name":"배우","email":"actor@example.com","password":"password12345",
             "termsVersion":"test-v1","privacyVersion":"test-v1","termsAccepted":true,"privacyAccepted":true}
            """;
    private static final String LOGIN = """
            {"email":"actor@example.com","password":"password12345"}
            """;

    @BeforeEach
    void clean() {
        refreshTokens.deleteAll();
        consents.deleteAll();
        users.deleteAll();
    }

    @Test
    void a01TokenAndSameSessionAllowSignupAndLogin() throws Exception {
        var csrf = issue();
        mvc.perform(post("/api/auth/signup").session(csrf.session()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/login").session(csrf.session()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN)
                        .with(request -> { request.setRemoteAddr(UUID.randomUUID().toString()); return request; }))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly("refreshToken", true));
        assertThat(users.count()).isEqualTo(1);
        assertThat(refreshTokens.count()).isEqualTo(1);
    }

    @Test
    void missingWrongOrForeignSessionTokenBlocksBothEndpoints() throws Exception {
        var csrf = issue();
        var other = issue();
        for (String path : new String[]{"/api/auth/signup", "/api/auth/login"}) {
            for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                    post(path), // 세션과 토큰 모두 없음
                    post(path).session(csrf.session()), // 세션만 있음
                    post(path).header(csrf.header(), csrf.token()), // 토큰만 있고 세션 없음
                    post(path).session(csrf.session()).header(csrf.header(), "wrong"),
                    post(path).session(other.session()).header(csrf.header(), csrf.token())}) {
                mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(path.endsWith("signup") ? SIGNUP : LOGIN))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false))
                        .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"))
                        .andExpect(header().doesNotExist("Set-Cookie"));
            }
        }
        assertThat(users.count()).isZero();
        assertThat(consents.count()).isZero();
        assertThat(refreshTokens.count()).isZero();
    }

    @Test
    void invalidatedSessionRequiresNewTokenAndSession() throws Exception {
        var old = issue();
        old.session().invalidate();
        var renewed = issue();

        mvc.perform(post("/api/auth/signup").session(renewed.session()).header(old.header(), old.token())
                        .contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        assertThat(users.count()).isZero();

        mvc.perform(post("/api/auth/signup").session(renewed.session()).header(renewed.header(), renewed.token())
                        .contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isCreated());
    }

    @Test
    void finalContractReturnsStoredExpiryWithoutExtendingOnRepeatedGet() throws Exception {
        var first = mvc.perform(get("/api/auth/csrf").with(com.personalab.vectoract.vector_act_was.support.ApplicationCsrf.applicationCsrf())).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) first.getRequest().getSession(false);
        java.util.Map<String, Object> data = JsonPath.read(first.getResponse().getContentAsString(), "$.data");
        assertThat(data).containsOnlyKeys("csrfToken", "expiresAt");
        var expiry = java.time.OffsetDateTime.parse((String) data.get("expiresAt"));
        assertThat(expiry).isEqualTo(csrfTokens.getExpiresAt(first.getRequest()));
        assertThat(expiry.isAfter(java.time.OffsetDateTime.now())).isTrue();
        mvc.perform(get("/api/auth/csrf").with(com.personalab.vectoract.vector_act_was.support.ApplicationCsrf.applicationCsrf()).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").value(data.get("expiresAt")));
    }

    @Test
    void expiredTokenIsRejectedAndA01RenewalWorksInSameSession() throws Exception {
        var old = issue();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setSession(old.session());
        var token = csrfTokens.loadToken(request);
        // 운영과 같은 저장 구조에 이미 만료된 발급 시각을 넣어 기다림 없이 만료를 재현합니다.
        var past = java.time.Clock.fixed(java.time.Instant.now().minusSeconds(60), java.time.ZoneOffset.UTC);
        new com.personalab.vectoract.vector_act_was.global.auth.ExpiringCsrfTokenRepository(1, past)
                .saveToken(token, request, new org.springframework.mock.web.MockHttpServletResponse());
        mvc.perform(post("/api/auth/signup").session(old.session()).header(old.header(), old.token())
                        .contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        assertThat(users.count()).isZero();
        var renewed = mvc.perform(get("/api/auth/csrf").session(old.session())).andExpect(status().isOk()).andReturn();
        String renewedToken = JsonPath.read(renewed.getResponse().getContentAsString(), "$.data.csrfToken");
        assertThat(renewedToken).isNotEqualTo(old.token());
        mvc.perform(post("/api/auth/signup").session(old.session()).header("X-CSRF-TOKEN", renewedToken)
                        .contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isCreated());
    }

    private Issued issue() throws Exception {
        var result = mvc.perform(get("/api/auth/csrf").with(com.personalab.vectoract.vector_act_was.support.ApplicationCsrf.applicationCsrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.csrfToken").isNotEmpty())
                .andExpect(jsonPath("$.data.csrfToken").isString())
                .andExpect(jsonPath("$.data.headerName").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.expiresAt").isString())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return new Issued(session, JsonPath.read(result.getResponse().getContentAsString(), "$.data.csrfToken"),
                "X-CSRF-TOKEN");
    }

    private record Issued(MockHttpSession session, String token, String header) { }
}
