package com.personalab.vectoract.vector_act_was.domain.upload.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/** U03 업로드 완료·검증 접수 요청 본문입니다. parts는 1..partCount 오름차순, 중복 불가(Service에서 검증). */
public record UploadCompleteRequest(@NotEmpty @Valid List<PartInput> parts) {

    public record PartInput(
            @NotNull @Min(1) Integer partNumber,
            @NotBlank String etag,
            @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String sha256) {}
}
