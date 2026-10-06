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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:update-me;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
class UpdateMeIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AccessTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private static final OffsetDateTime OLD_UPDATED_AT = OffsetDateTime.parse("2020-01-01T00:00:00Z");

    @BeforeEach
    void prepare() {
        users.deleteAll();
        user = users.saveAndFlush(User.create("actor@example.com", "password-hash", "Original"));
        jdbc.update("UPDATE users SET updated_at = ? WHERE id = ?", OLD_UPDATED_AT, user.getId());
    }

    @Test
    void updatesOnlyTokenOwnerAndReturnsSpecifiedFieldsWithoutCsrf() throws Exception {
        var other = users.saveAndFlush(User.create("other@example.com", "other-hash", "Other"));
        var response = mvc.perform(patch("/api/users/me")
                        .param("userId", other.getId().toString())
                        .header("Authorization", "Bearer " + tokens.issue(user.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"새 이름\",\"userId\":\"" + other.getId()
                                + "\",\"email\":\"changed@example.com\",\"accountStatus\":\"WITHDRAWN\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.name").value("새 이름"))
                .andExpect(jsonPath("$.data.email").value(user.getEmail()))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> data = JsonPath.read(response, "$.data");
        assertThat(data).containsOnlyKeys("userId", "name", "email", "createdAt");
        assertThat(OffsetDateTime.parse((String) data.get("createdAt")).toInstant().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(user.getCreatedAt().toInstant().truncatedTo(ChronoUnit.MILLIS));
        var saved = users.findById(user.getId()).orElseThrow();
        assertThat(saved.getName()).isEqualTo("새 이름");
        assertThat(saved.getUpdatedAt()).isAfter(OLD_UPDATED_AT);
        assertThat(saved.getEmail()).isEqualTo(user.getEmail());
        assertThat(saved.getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(saved.getAccountStatus()).isEqualTo(User.AccountStatus.ACTIVE);
        assertThat(saved.getCreatedAt().toInstant().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(user.getCreatedAt().toInstant().truncatedTo(ChronoUnit.MILLIS));
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getPurgeAt()).isNull();
        assertThat(users.findById(other.getId()).orElseThrow().getName()).isEqualTo("Other");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 30})
    void acceptsNameLengthBoundaries(int length) throws Exception {
        String name = "가".repeat(length);
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + tokens.issue(user.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value(name));
        assertThat(users.findById(user.getId()).orElseThrow().getName()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}",
            "{\"name\":\"1234567890123456789012345678901\"}", "{", ""})
    void rejectsInvalidBodyWithoutChangingUser(String body) throws Exception {
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + tokens.issue(user.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertUnchanged();
    }

    @Test
    void missingUserReturnsResourceNotFound() throws Exception {
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + tokens.issue(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Updated\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertUnchanged();
    }

    @Test
    void withdrawnUserReturnsResourceNotFoundWithoutChanges() throws Exception {
        jdbc.update("UPDATE users SET account_status = 'WITHDRAWN' WHERE id = ?", user.getId());
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + tokens.issue(user.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Updated\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertUnchanged();
        assertThat(users.findById(user.getId()).orElseThrow().getAccountStatus()).isEqualTo(User.AccountStatus.WITHDRAWN);
    }

    @Test
    void missingTokenReturnsUnauthorized() throws Exception {
        mvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Updated\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer broken-token", "Bearer ", "Basic abc"})
    void invalidTokenReturnsUnauthorized(String authorization) throws Exception {
        mvc.perform(patch("/api/users/me").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Updated\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("ACCESS_INVALID"));
        assertUnchanged();
    }

    @Test
    void sameNameSucceedsWithoutUnnecessaryDatabaseUpdate() throws Exception {
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + tokens.issue(user.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Original\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Original"));
        assertUnchanged();
    }

    private void assertUnchanged() {
        var saved = users.findById(user.getId()).orElseThrow();
        assertThat(saved.getName()).isEqualTo("Original");
        assertThat(saved.getUpdatedAt()).isEqualTo(OLD_UPDATED_AT);
    }
}
