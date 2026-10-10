package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKeyRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 서명 키·접속 주소를 설정하지 않은 서버의 C03 동작을 확인합니다(기본 설정 그대로 실행).
 * 서버가 기동은 되지만 티켓 발급만 503으로 거절되어야 합니다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:connectionticketunconfigured;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class ConnectionTicketUnconfiguredTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired CoachingSessionRepository sessions;
    @Autowired SessionVideoRepository videos;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AccessTokenProvider accessTokens;

    @Test
    void withoutSigningKeyIsServiceUnavailable() throws Exception {
        videos.deleteAll();
        idempotencyKeys.deleteAll();
        sessions.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        var session = CoachingSession.prepare(user.getId(), "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, OffsetDateTime.now());
        session.startRecording(OffsetDateTime.now());
        sessions.saveAndFlush(session);

        mvc.perform(post("/api/coaching-sessions/" + session.getId() + "/connection-tickets")
                        .header("Authorization", "Bearer " + accessTokens.issue(user.getId())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
    }
}
