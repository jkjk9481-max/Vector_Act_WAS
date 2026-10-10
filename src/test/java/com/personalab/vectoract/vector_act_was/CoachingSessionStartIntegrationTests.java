package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKeyRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * C02 촬영 시작 통합 테스트. 실제 스프링 컨텍스트를 띄우고 MockMvc로 HTTP 요청을 보내,
 * 보안 필터 → 컨트롤러 → 서비스 → DB(H2)까지 전체 흐름을 검증합니다.
 * 각 테스트는 "무엇이 응답으로 나가고(HTTP), 무엇이 DB에 남았는가"를 함께 확인합니다.
 */
@SpringBootTest(properties = {
        // 테스트마다 독립된 인메모리 H2를 씁니다. 이름(coachingstart)을 다른 테스트 클래스와 다르게 해 데이터가 섞이지 않습니다.
        // INIT의 CREATE DOMAIN은 엔티티의 TIMESTAMPTZ 컬럼 타입을 H2가 이해하도록 별칭을 만듭니다.
        "spring.datasource.url=jdbc:h2:mem:coachingstart;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        // 백그라운드 정리 스케줄러가 테스트 도중 데이터를 지우지 않도록 끕니다.
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class CoachingSessionStartIntegrationTests {
    // 명세가 허용하는 입력 MIME. 테스트 전반에서 "정상 값"으로 씁니다.
    private static final String WEBM = "video/webm;codecs=vp8,opus";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired CoachingSessionRepository sessions;
    @Autowired SessionVideoRepository videos;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String bearer;

    /** 매 테스트 전에 모든 테이블을 비우고 새 회원과 그 회원의 Access Token을 만듭니다(테스트 간 독립 보장). */
    @BeforeEach
    void prepare() {
        videos.deleteAll();
        idempotencyKeys.deleteAll();
        sessions.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    /** C01을 거치지 않고 DB에 CREATED 상태의 세션을 바로 만듭니다(C02만 따로 검증하기 위한 준비 단계). */
    private CoachingSession prepared(UUID userId) {
        return sessions.saveAndFlush(CoachingSession.prepare(userId, "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, OffsetDateTime.now()));
    }

    /** 요청 본문 JSON을 만듭니다. 숫자 필드를 Object로 받아 정수·소수·잘못된 값을 모두 넣을 수 있습니다. */
    private static String body(String mimeType, Object width, Object height, Object frameRate) {
        return "{\"mimeType\":\"" + mimeType + "\",\"width\":" + width + ",\"height\":" + height
                + ",\"frameRate\":" + frameRate + "}";
    }

    private ResultActions start(UUID sessionId, String body) throws Exception {
        return start(sessionId, body, bearer);
    }

    private ResultActions start(UUID sessionId, String body, String authorization) throws Exception {
        return mvc.perform(post("/api/coaching-sessions/" + sessionId + "/start")
                .contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", authorization));
    }

    @Test
    // 정상 흐름: 응답(HTTP)과 DB 상태를 모두 확인합니다.
    void startsRecordingAndStoresInputVideoInfo() throws Exception {
        var session = prepared(user.getId());

        start(session.getId(), body(WEBM, 1920, 1080, 30)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(session.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("RECORDING"))
                .andExpect(jsonPath("$.data.videoStatus").value("UPLOADING"))
                .andExpect(jsonPath("$.data.analysisStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.endedAt").value((Object) null))
                .andExpect(jsonPath("$.data.durationMs").value((Object) null))
                .andExpect(jsonPath("$.data.videoExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.scriptContent").value("대본"))
                .andExpect(jsonPath("$.data.coaching.visualEnabled").value(true));

        var saved = sessions.findById(session.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(CoachingSession.Status.RECORDING);
        assertThat(Duration.between(saved.getStartedAt(), saved.getVideoExpiresAt())).isEqualTo(Duration.ofDays(30));
        var video = videos.findAll().getFirst();
        assertThat(video.getSessionId()).isEqualTo(session.getId());
        assertThat(video.getInputContentType()).isEqualTo(WEBM);
        assertThat(video.getWidth()).isEqualTo(1920);
        assertThat(video.getHeight()).isEqualTo(1080);
        assertThat(video.getFrameRate()).isEqualByComparingTo("30.00");
        assertThat(video.getExpiresAt()).isEqualTo(saved.getVideoExpiresAt());
        assertThat(video.getObjectKey()).isNull();
    }

    @Test
    // MP4 형식, 대소문자·공백이 섞인 MIME, 소수 프레임레이트(29.97)를 허용하고 정식 표기로 저장하는지 확인합니다.
    void acceptsMp4AndFractionalFrameRate() throws Exception {
        var session = prepared(user.getId());

        // 대소문자·공백이 달라도 지원 형식이면 정식 표기로 저장합니다.
        start(session.getId(), body(" video/MP4; codecs=avc1.42E01E,mp4a.40.2", 640, 480, "29.97"))
                .andExpect(status().isOk());

        var video = videos.findAll().getFirst();
        assertThat(video.getInputContentType()).isEqualTo("video/mp4;codecs=avc1.42e01e,mp4a.40.2");
        assertThat(video.getFrameRate()).isEqualByComparingTo(new BigDecimal("29.97"));
    }

    @Test
    // 지원하지 않는 MIME은 415이고, 세션 상태와 영상 행이 전혀 바뀌지 않아야 합니다(검증이 DB 변경보다 먼저).
    void unsupportedMimeTypeIs415AndSessionStaysCreated() throws Exception {
        var session = prepared(user.getId());

        for (String mime : new String[]{"video/webm", "video/webm;codecs=vp9,opus", "audio/webm;codecs=opus", ""}) {
            start(session.getId(), body(mime, 1920, 1080, 30)).andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        }
        assertThat(sessions.findById(session.getId()).orElseThrow().getStatus())
                .isEqualTo(CoachingSession.Status.CREATED);
        assertThat(videos.count()).isZero();
    }

    @Test
    // 범위를 벗어난 숫자와 누락된 필드, 깨진 JSON은 모두 400 VALIDATION_ERROR입니다.
    void invalidNumbersAreValidationError() throws Exception {
        var session = prepared(user.getId());
        String[] bodies = {
                body(WEBM, 0, 1080, 30), body(WEBM, 1921, 1080, 30),
                body(WEBM, 1920, 0, 30), body(WEBM, 1920, 1081, 30),
                body(WEBM, 1920, 1080, 0.5), body(WEBM, 1920, 1080, 31),
                "{\"mimeType\":\"" + WEBM + "\",\"width\":1920,\"height\":1080}",
                "{\"width\":1920,\"height\":1080,\"frameRate\":30}",
                "{}", "not-json"};
        for (String body : bodies) {
            start(session.getId(), body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        assertThat(videos.count()).isZero();
    }

    @Test
    // 허용 범위의 최솟값(1, 1, 1)도 통과해야 합니다(경계값 테스트).
    void boundaryValuesAreAccepted() throws Exception {
        var session = prepared(user.getId());

        start(session.getId(), body(WEBM, 1, 1, 1)).andExpect(status().isOk());
    }

    @Test
    // CREATED가 아닌 모든 상태(이미 시작됨, 종료됨)에서는 409이고 영상 행이 추가로 생기지 않아야 합니다.
    void startingTwiceOrAfterEndIsStateConflict() throws Exception {
        var session = prepared(user.getId());
        start(session.getId(), body(WEBM, 1920, 1080, 30)).andExpect(status().isOk());

        start(session.getId(), body(WEBM, 1920, 1080, 30)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));

        for (String ended : new String[]{"FINALIZING", "COMPLETED", "FAILED", "CANCELED"}) {
            jdbc.update("update coaching_sessions set status = ?", ended);
            start(session.getId(), body(WEBM, 1920, 1080, 30)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        }
        assertThat(videos.count()).isEqualTo(1);
    }

    @Test
    // 타인 소유, 존재하지 않음, 삭제 표시됨은 구분되지 않는 같은 404여야 하고 타인 세션은 그대로여야 합니다.
    void otherUsersOrUnknownOrDeletedSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = prepared(other.getId());
        var mine = prepared(user.getId());
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", mine.getId());

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID(), mine.getId()}) {
            start(id, body(WEBM, 1920, 1080, 30)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
        assertThat(sessions.findById(othersSession.getId()).orElseThrow().getStatus())
                .isEqualTo(CoachingSession.Status.CREATED);
    }

    @Test
    // 경로의 sessionId가 UUID 형식이 아니면 400입니다.
    void malformedSessionIdIsValidationError() throws Exception {
        mvc.perform(post("/api/coaching-sessions/not-a-uuid/start").contentType(MediaType.APPLICATION_JSON)
                        .content(body(WEBM, 1920, 1080, 30)).header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    // Authorization 헤더가 없으면 컨트롤러에 도달하기 전에 보안 필터가 401로 막아야 합니다.
    void requiresBearerToken() throws Exception {
        var session = prepared(user.getId());

        mvc.perform(post("/api/coaching-sessions/" + session.getId() + "/start")
                        .contentType(MediaType.APPLICATION_JSON).content(body(WEBM, 1920, 1080, 30)))
                .andExpect(status().isUnauthorized());
        assertThat(videos.count()).isZero();
    }
}
