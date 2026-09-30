package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** JSON 요청을 담고, DB에 저장하기 전에 입력 조건을 검사합니다. */
public record SignupRequest(
        // @NotBlank는 null, 빈 문자열, 공백뿐인 입력을 거절하고 @Size는 문자열 길이를 검사합니다.
        // @Pattern은 허용할 문자 규칙입니다. \p{L}은 한글·영문 등 유니코드 문자입니다.
        // 일반 띄어쓰기만 함께 허용하고 숫자, 기호, 이모지, 탭과 줄바꿈은 거절합니다.
        @NotBlank @Size(min = 1, max = 30)
        @Pattern(regexp = "[\\p{L} ]+", message = "이름은 문자만 사용할 수 있습니다.") String name,
        // @Email은 이메일 형식을 검사합니다. 필수 여부는 @NotBlank가 따로 검사합니다.
        @NotBlank @Email @Size(max = 254) String email,
        // 문자 길이는 8~32자이고, 아래 별도 검증에서 UTF-8 72바이트 제한도 확인합니다.
        @NotNull @Size(min = 8, max = 32, message = "비밀번호는 8~32자여야 합니다.") String password,
        @NotBlank @Size(min = 1, max = 32) String termsVersion,
        @NotBlank @Size(min = 1, max = 32) String privacyVersion,
        // Boolean은 null도 담을 수 있어 누락과 false를 구분할 수 있습니다.
        // @AssertTrue만으로는 null을 거절하지 않으므로 @NotNull도 함께 필요합니다.
        @NotNull @AssertTrue Boolean termsAccepted,
        @NotNull @AssertTrue Boolean privacyAccepted
) {
    public SignupRequest {
        // record의 간결한 생성자입니다. JSON에서 읽은 값이 필드에 들어가기 전에 정리합니다.
        // 생성할 때 정리하므로 이름 길이 검사와 이메일 중복 검사는 정규화된 값으로 진행됩니다.
        // 조건 ? 참일 때 값 : 거짓일 때 값. null에 strip()을 호출하면 오류가 나므로 먼저 확인합니다.
        name = name == null ? null : name.strip();
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        // 비밀번호의 공백은 실제 비밀번호의 일부이므로 제거하지 않습니다.
    }

    // 검증용 메서드를 JSON 필드로 취급하지 않도록 합니다.
    @JsonIgnore
    @AssertTrue(message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
    public boolean isPasswordWithinByteLimit() {
        // 한글 등은 한 글자가 여러 바이트입니다. 문자 수 제한과 BCrypt 바이트 제한을 따로 검사합니다.
        // null 검사는 위 @NotNull에 맡기고 여기서는 UTF-8 바이트 길이만 검사합니다.
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    // record가 자동으로 만드는 toString() 대신 비밀번호를 제외한 문자열을 사용합니다.
    // 요청 객체를 로그에 출력하더라도 평문 비밀번호가 포함되지 않게 합니다.
    @Override
    public String toString() {
        return "SignupRequest[password=REDACTED]";
    }
}
