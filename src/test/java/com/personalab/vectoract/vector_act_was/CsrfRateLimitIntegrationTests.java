package com.personalab.vectoract.vector_act_was;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.personalab.vectoract.vector_act_was.support.ApplicationCsrf.applicationCsrf;

/**
 * A01의 실제 보안 필터·인터셉터·Controller를 검증합니다.
 * 테스트에서는 횟수만 2회로 낮춰 적은 요청으로 제한 도달을 재현합니다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:csrfratelimit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.csrf-rate-limit.max-attempts=2",
        "auth.csrf-rate-limit.window-seconds=60",
        "auth.withdrawal.purge-enabled=false"})
@AutoConfigureMockMvc
class CsrfRateLimitIntegrationTests {
    @Autowired MockMvc mvc;

    @Test
    void defaultExpiryIsTwentyFourHoursFromIssuance() throws Exception {
        var before = Instant.now();
        var result = mvc.perform(get("/api/auth/csrf").with(applicationCsrf())
                        .with(r -> { r.setRemoteAddr(UUID.randomUUID().toString()); return r; }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.csrfToken").isString())
                .andExpect(jsonPath("$.data.expiresAt").isString()).andReturn();
        var after = Instant.now();
        String expiryText = JsonPath.read(result.getResponse().getContentAsString(), "$.data.expiresAt");
        var expiry = OffsetDateTime.parse(expiryText).toInstant();
        // 고정된 기본 TTL이 실제 응답에도 사용되는지 확인합니다.
        assertThat(expiry).isBetween(before.plus(Duration.ofHours(24)), after.plus(Duration.ofHours(24)));
    }

    @Test
    void thirdRequestIs429AndDoesNotCreateSessionOrReturnToken() throws Exception {
        String ip = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/auth/csrf").with(applicationCsrf())
                            .with(r -> { r.setRemoteAddr(ip); return r; }))
                    .andExpect(status().isOk());
        }
        var rejected = mvc.perform(get("/api/auth/csrf").with(applicationCsrf())
                        .with(r -> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.data.csrfToken").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        assertThat(rejected.getRequest().getSession(false)).isNull();

        // 제한은 IP별이므로 다른 주소의 정상 발급은 계속 가능해야 합니다.
        mvc.perform(get("/api/auth/csrf").with(applicationCsrf())
                        .with(r -> { r.setRemoteAddr(UUID.randomUUID().toString()); return r; }))
                .andExpect(status().isOk());
    }

    @Test
    void newSessionAndForgedForwardedHeaderDoNotBypassLimit() throws Exception {
        String ip = UUID.randomUUID().toString();
        var first = mvc.perform(get("/api/auth/csrf").with(applicationCsrf())
                        .with(r -> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) first.getRequest().getSession(false);
        String expiry = JsonPath.read(first.getResponse().getContentAsString(), "$.data.expiresAt");
        mvc.perform(get("/api/auth/csrf").with(applicationCsrf()).session(session)
                        .with(r -> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresAt").value(expiry));
        mvc.perform(get("/api/auth/csrf").with(applicationCsrf()).session(new MockHttpSession())
                        .header("X-Forwarded-For", "203.0.113.10")
                        .with(r -> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }
}
