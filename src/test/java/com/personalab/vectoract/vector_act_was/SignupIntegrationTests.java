package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 HTTP 처리와 테스트 DB 저장을 함께 확인합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class SignupIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired UserConsentRepository consents;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    private static final String REQUEST = """
            {"name":"배우","email":"  ACTOR@example.com  ","password":"password12345",
             "termsVersion":"test-v1","privacyVersion":"test-v1",
             "termsAccepted":true,"privacyAccepted":true}
            """;

    @BeforeEach
    void clean() {
        consents.deleteAll();
        users.deleteAll();
    }

    @Test
    void createsUserAndConsentsAndRejectsNormalizedDuplicate() throws Exception {
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").isNotEmpty())
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.name").value("배우"))
                .andExpect(jsonPath("$.data.email").value("actor@example.com"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        User user = users.findByEmail("actor@example.com").orElseThrow();
        assertThat(encoder.matches("password12345", user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordHash()).startsWith("$2");
        assertThat(consents.findAllByUserId(user.getId())).extracting(UserConsent::getConsentType)
                .containsExactlyInAnyOrder(UserConsent.ConsentType.TERMS, UserConsent.ConsentType.PRIVACY);
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST.replace("  ACTOR@example.com  ", "actor@example.com")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));
        assertThat(users.count()).isEqualTo(1);
        assertThat(consents.count()).isEqualTo(2);
    }

    @Test
    void rejectsUnknownVersions() throws Exception {
        for (String field : new String[]{"termsVersion", "privacyVersion"}) {
            mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST.replace("\"" + field + "\":\"test-v1\"", "\"" + field + "\":\"old\"")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("TERMS_VERSION_INVALID"));
        }
        assertThat(users.count()).isZero();
    }

    @Test
    void rejectsMissingFields() throws Exception {
        for (String field : new String[]{"name", "email", "password", "termsVersion", "privacyVersion", "termsAccepted", "privacyAccepted"}) {
            String body = REQUEST.replaceAll("\"" + field + "\":(\"[^\"]*\"|true),?", "")
                    .replaceAll(",\\s*}", "}");
            assertInvalid(body);
        }
    }

    @Test
    void rejectsInvalidValuesAndByteOverflow() throws Exception {
        for (String body : new String[]{REQUEST.replace("배우", "   "),
                REQUEST.replace("배우", "a".repeat(31)),
                REQUEST.replace("  ACTOR@example.com  ", "invalid"),
                REQUEST.replace("  ACTOR@example.com  ", "a".repeat(243) + "@example.com"),
                REQUEST.replace("password12345", "a".repeat(7)),
                REQUEST.replace("password12345", "a".repeat(33)),
                REQUEST.replace("password12345", "가".repeat(25)),
                REQUEST.replace("test-v1", "a".repeat(33)), REQUEST.replace("test-v1", ""),
                REQUEST.replace("true", "false"), REQUEST.replace("true", "null"), "{", ""}) {
            assertInvalid(body);
        }
    }

    @Test
    void acceptsPasswordBoundaries() throws Exception {
        for (String password : new String[]{"a".repeat(8), "a".repeat(32), "가".repeat(24)}) {
            clean();
            mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST.replace("password12345", password)))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void rejectsNumbersAndSpecialCharactersInName() throws Exception {
        // JSON에 이스케이프한 탭과 줄바꿈도 보내 실제 이름 검증에서 거절되는지 확인합니다.
        for (String name : new String[]{"배우1", "배우１２", "배우@", "김_배우", "김-배우",
                "O'Neil", "배우😀", "김\\t배우", "김\\n배우", "김 배우", " 배우", "배우 ",
                "\\t배우", "배우\\n", "김\u00a0배우", "김\u3000배우"}) {
            assertInvalid(REQUEST.replace("배우", name));
        }
    }

    @Test
    void acceptsLettersOnlyInName() throws Exception {
        for (String name : new String[]{"김배우", "JohnSmith", "Élodie", "김", "가".repeat(30)}) {
            clean();
            mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST.replace("배우", name)))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value(name));
        }
    }

    @Test
    void passwordIsNotEchoed() throws Exception {
        var result = mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST.replace("password12345", "secret")))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret");
    }

    @Test
    void secondConsentFailureRollsBackEverything() throws Exception {
        // 두 번째 동의 INSERT를 실패시켜 Service 자체의 트랜잭션이 전체를 취소하는지 확인합니다.
        jdbc.execute("ALTER TABLE user_consents ADD CONSTRAINT test_fail_privacy CHECK (consent_type <> 'PRIVACY')");
        try {
            mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                    .andExpect(status().isInternalServerError());
            assertThat(users.count()).isZero();
            assertThat(consents.count()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE user_consents DROP CONSTRAINT test_fail_privacy");
        }
    }

    private void assertInvalid(String body) throws Exception {
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(users.count()).isZero();
        assertThat(consents.count()).isZero();
    }
}
