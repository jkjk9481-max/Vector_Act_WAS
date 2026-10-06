package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

public record ChangePasswordRequest(
        @NotNull @Size(min = 1) String currentPassword,
        @NotNull @Size(min = 8, max = 32) String newPassword,
        @NotNull @Size(min = 1) String newPasswordConfirm) {

    // 공백도 비밀번호의 일부입니다. 입력을 정규화하거나 잘라내지 않습니다.
    @JsonIgnore
    @AssertTrue(message = "새 비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
    public boolean isNewPasswordWithinByteLimit() {
        return newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() {
        return "ChangePasswordRequest[passwords=REDACTED]";
    }
}
