package com.personalab.vectoract.vector_act_was.domain.member.presentation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

/**
 * 클라이언트가 보낸 JSON을 담는 요청 DTO입니다. DB 엔티티와 역할이 다릅니다.
 * record는 필드·생성자·접근자를 자동으로 만듭니다. 예: request.newPassword().
 * Controller의 @Valid가 아래 어노테이션과 검증 메서드를 검사합니다.
 */
public record ChangePasswordRequest(
        // @NotNull은 누락/null, @Size(min=1)은 빈 문자열을 거절합니다.
        // 현재 비밀번호의 일치 여부는 저장된 해시가 필요하므로 Service에서 검사합니다.
        @NotNull @Size(min = 1) String currentPassword,
        // 새 비밀번호의 길이는 8~32입니다. @Size의 Java String 길이는 UTF-16 코드 단위입니다.
        // 한글·영문은 보통 1단위이고 일부 이모지는 2단위입니다. 바이트 길이는 아래에서 별도 검사합니다.
        @NotNull @Size(min = 8, max = 32) String newPassword,
        // 필수 여부만 여기서 검사합니다. 새 비밀번호와의 불일치는 Service에서 PASSWORD_MISMATCH로 구분합니다.
        @NotNull @Size(min = 1) String newPasswordConfirm) {

    // 공백도 비밀번호의 일부입니다. 입력을 정규화하거나 잘라내지 않습니다.
    // @JsonIgnore는 이 보조 검증 속성을 JSON 입출력 대상에서 제외합니다.
    // @AssertTrue는 메서드의 반환값이 true여야 검증을 통과한다는 뜻입니다.
    @JsonIgnore
    @AssertTrue(message = "새 비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
    public boolean isNewPasswordWithinByteLimit() {
        // UTF-8에서 한글은 보통 글자당 3바이트이므로 문자 수와 바이트 수는 다릅니다.
        // 예: 한글 25자는 길이 조건에는 맞지만 75바이트라 BCrypt의 72바이트 한도를 넘습니다.
        // null은 위 @NotNull에 맡깁니다. 여기서는 null일 때 getBytes를 호출하지 않습니다.
        return newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    // record의 기본 toString은 모든 필드 값을 출력합니다. 이를 재정의해 로그에서 평문 노출을 막습니다.
    // 이 처리는 디버그 문자열을 가리는 것이며, DB에 저장할 해시를 만드는 작업과는 별개입니다.
    @Override
    public String toString() {
        return "ChangePasswordRequest[passwords=REDACTED]";
    }
}
