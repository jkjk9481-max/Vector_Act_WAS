package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateMeRequest(
        @NotBlank @Size(min = 1, max = 30)
        @Pattern(regexp = "\\p{L}+", message = "이름은 공백, 숫자, 특수문자 없이 문자만 입력해야 합니다.")
        String name) {
}
