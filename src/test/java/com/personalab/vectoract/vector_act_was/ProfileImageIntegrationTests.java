package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.member.business.InMemoryProfileImageStorage;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:profileimage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        // 회원마다 별도 카운터를 쓰므로 테스트가 새 회원을 만들면 서로 영향을 주지 않습니다.
        "auth.profile-image-rate-limit.max-attempts=4"})
@AutoConfigureMockMvc
class ProfileImageIntegrationTests {
    private static final String PATH = "/api/users/me/profile-image";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean InMemoryProfileImageStorage storage;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        storage.keys().forEach(storage::delete);
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    private static byte[] image(String format, int type) throws Exception {
        var image = new BufferedImage(8, 8, type);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static byte[] png() throws Exception { return image("png", BufferedImage.TYPE_INT_ARGB); }

    /** SOI 직후에 EXIF(APP1) 세그먼트를 끼워 넣어 위치정보가 들어 있는 JPEG를 흉내냅니다. */
    private static byte[] jpegWithExif() throws Exception {
        byte[] jpeg = image("jpeg", BufferedImage.TYPE_INT_RGB);
        byte[] payload = "Exif\0\0SECRET-GPS-DATA".getBytes(StandardCharsets.ISO_8859_1);
        int length = payload.length + 2;
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private ResultActions upload(byte[] content, String name, String type) throws Exception {
        return mvc.perform(multipart(HttpMethod.PUT, PATH).file(new MockMultipartFile("file", name, type, content))
                .header("Authorization", bearer));
    }

    private String keyOf(User found) {
        return users.findById(found.getId()).orElseThrow().getProfileImageKey();
    }

    @Test
    void storesPngAndReturnsPresignedUrlWithSameFieldsAsMe() throws Exception {
        String response = upload(png(), "me.png", "image/png").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.email").value(user.getEmail()))
                .andExpect(jsonPath("$.data.profileImageUrl").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String key = keyOf(user);
        assertThat(key).startsWith("profile-images/" + user.getId() + "/").endsWith(".png");
        assertThat(storage.contains(key)).isTrue();
        assertThat(ImageIO.read(new ByteArrayInputStream(storage.get(key)))).isNotNull();
        assertThat(users.findById(user.getId()).orElseThrow().getProfileImageUpdatedAt()).isNotNull();
        assertThat(response).contains(key);
        mvc.perform(get("/api/users/me").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").isNotEmpty());
    }

    @Test
    void stripsExifByReencodingJpeg() throws Exception {
        byte[] original = jpegWithExif();
        assertThat(new String(original, StandardCharsets.ISO_8859_1)).contains("SECRET-GPS-DATA");
        upload(original, "photo.jpg", "image/jpeg").andExpect(status().isOk());
        String key = keyOf(user);
        assertThat(key).endsWith(".jpg");
        byte[] stored = storage.get(key);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("Exif", "SECRET-GPS-DATA");
        assertThat(ImageIO.read(new ByteArrayInputStream(stored))).isNotNull();
    }

    @Test
    void contentIsDecodedNotTrustedFromHeaderOrFilename() throws Exception {
        // 헤더·확장자는 jpeg이지만 실제 내용이 PNG이면 PNG로 저장합니다.
        upload(png(), "fake.jpg", "image/jpeg").andExpect(status().isOk());
        assertThat(keyOf(user)).endsWith(".png");
    }

    @Test
    void replacingDeletesPreviousObjectAfterCommit() throws Exception {
        upload(png(), "a.png", "image/png").andExpect(status().isOk());
        String first = keyOf(user);
        upload(image("jpeg", BufferedImage.TYPE_INT_RGB), "b.jpg", "image/jpeg").andExpect(status().isOk());
        String second = keyOf(user);
        assertThat(second).isNotEqualTo(first);
        assertThat(storage.contains(first)).isFalse();
        assertThat(storage.contains(second)).isTrue();
        assertThat(storage.keys()).containsExactly(second);
    }

    @Test
    void deleteClearsKeyRemovesObjectAndIsIdempotent() throws Exception {
        upload(png(), "a.png", "image/png").andExpect(status().isOk());
        String key = keyOf(user);
        mvc.perform(delete(PATH).header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.profileImageUrl").value((Object) null));
        assertThat(keyOf(user)).isNull();
        assertThat(storage.contains(key)).isFalse();
        mvc.perform(delete(PATH).header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value((Object) null));
    }

    @Test
    void unsupportedOrUndecodableContentIsRejectedWithoutSideEffects() throws Exception {
        upload(image("gif", BufferedImage.TYPE_INT_RGB), "a.png", "image/png").andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        upload("not an image".getBytes(StandardCharsets.UTF_8), "a.png", "image/png")
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(put(PATH).header("Authorization", bearer).contentType("application/json").content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        assertThat(keyOf(user)).isNull();
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void emptyMissingAndOversizedFilesAreRejected() throws Exception {
        upload(new byte[0], "a.png", "image/png").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(multipart(HttpMethod.PUT, PATH).header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        upload(new byte[5 * 1024 * 1024 + 1], "a.png", "image/png").andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
        assertThat(keyOf(user)).isNull();
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void requiresBearerAndNotCsrf() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, PATH).file(new MockMultipartFile("file", "a.png", "image/png", png())))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH)).andExpect(status().isUnauthorized());
        // 위 성공 케이스들은 CSRF 없이 Bearer만으로 통과합니다.
        upload(png(), "a.png", "image/png").andExpect(status().isOk());
    }

    @Test
    void withdrawnUserGetsNotFoundAndUploadedObjectIsCleanedUp() throws Exception {
        jdbc.update("UPDATE users SET account_status = 'WITHDRAWN' WHERE id = ?", user.getId());
        upload(png(), "a.png", "image/png").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(delete(PATH).header("Authorization", bearer)).andExpect(status().isNotFound());
        assertThat(keyOf(user)).isNull();
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void storageFailureKeepsPreviousImageAndReturnsServerError() throws Exception {
        upload(png(), "a.png", "image/png").andExpect(status().isOk());
        String key = keyOf(user);
        doThrow(new IllegalStateException("storage down")).when(storage).put(any(), any(), any());
        upload(png(), "b.png", "image/png").andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        assertThat(keyOf(user)).isEqualTo(key);
        assertThat(storage.keys()).containsExactly(key);
    }

    @Test
    void failedCleanupOfPreviousObjectDoesNotFailTheRequest() throws Exception {
        upload(png(), "a.png", "image/png").andExpect(status().isOk());
        String first = keyOf(user);
        doThrow(new IllegalStateException("storage down")).when(storage).delete(first);
        upload(png(), "b.png", "image/png").andExpect(status().isOk());
        assertThat(keyOf(user)).isNotEqualTo(first);
    }

    @Test
    void rateLimitIsPerUser() throws Exception {
        for (int i = 0; i < 4; i++) upload(png(), "a.png", "image/png").andExpect(status().isOk());
        upload(png(), "a.png", "image/png").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른 회원"));
        mvc.perform(multipart(HttpMethod.PUT, PATH).file(new MockMultipartFile("file", "a.png", "image/png", png()))
                .header("Authorization", "Bearer " + accessTokens.issue(other.getId()))).andExpect(status().isOk());
    }
}
