package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;

public record PasswordResetRequestBody(
        @NotBlank @Size(max = 255) String resetToken,
        @NotNull @Size(min = 8, max = 32) String newPassword,
        @NotNull @Size(min = 1) String newPasswordConfirm) {

    @JsonIgnore
    @AssertTrue(message = "새 비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
    public boolean isNewPasswordWithinByteLimit() {
        return newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() { return "PasswordResetRequestBody[credentials=REDACTED]"; }
}
