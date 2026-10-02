package com.personalab.vectoract.vector_act_was;

import com.jayway.jsonpath.JsonPath;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.User;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:me;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
class MeIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AccessTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    private User user;

    @BeforeEach
    void prepare() {
        users.deleteAll();
        user = users.saveAndFlush(User.create("actor@example.com", "secret-password-hash", "배우"));
    }

    @Test
    void returnsOnlyTheFourSpecifiedFieldsForTokenOwner() throws Exception {
        var other = users.saveAndFlush(User.create("other@example.com", "other-hash", "다른배우"));
        var response = mvc.perform(get("/api/users/me").param("userId", other.getId().toString())
                        .header("Authorization", "Bearer " + tokens.issue(user.getId())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.name").value("배우"))
                .andExpect(jsonPath("$.data.email").value("actor@example.com"))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> data = JsonPath.read(response, "$.data");
        assertThat(data).containsOnlyKeys("userId", "name", "email", "createAt");
        assertThat(OffsetDateTime.parse((String) data.get("createAt")).toInstant().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(user.getCreatedAt().toInstant().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void unknownUserReturnsResourceNotFound() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokens.issue(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void withdrawnUserReturnsResourceNotFound() throws Exception {
        jdbc.update("UPDATE users SET account_status = 'WITHDRAWN' WHERE id = ?", user.getId());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokens.issue(user.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void missingTokenReturnsUnauthorizedAndPreviousAuthenticationDoesNotLeak() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokens.issue(user.getId())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer ", "Bearer broken-token", "Basic abc", "Bearer", ""})
    void rejectsInvalidAuthorization(String authorization) throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", authorization))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() throws Exception {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var otherProvider = new AccessTokenProvider(java.util.Base64.getEncoder().encodeToString(key), "vector-act-test");
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + otherProvider.issue(user.getId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }
}
