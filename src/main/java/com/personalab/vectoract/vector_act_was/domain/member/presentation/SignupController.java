package com.personalab.vectoract.vector_act_was.domain.member.presentation;

import com.personalab.vectoract.vector_act_was.domain.member.business.SignupService;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.SignupRequest;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.SignupResponse;
import com.personalab.vectoract.vector_act_was.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// HTTP 요청을 받고 메서드 반환값을 JSON 응답 본문으로 변환하는 Controller입니다.
@RestController
// Lombok이 final 필드(signupService)를 받는 생성자를 만듭니다. Spring이 Service를 주입합니다.
@RequiredArgsConstructor
// 아래 메서드 경로 /signup과 합쳐 최종 주소는 /api/auth/signup이 됩니다.
@RequestMapping("/api/auth")
public class SignupController {
    private final SignupService signupService;

    @PostMapping("/signup")
    // @RequestBody: 요청 JSON → SignupRequest 객체. @Valid: DTO에 적힌 검증 규칙 실행.
    // 반환 타입: HTTP 응답(ResponseEntity) 안에 공통 본문(ApiResponse), 그 안에 가입 결과가 들어갑니다.
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest request) {
        // @Valid 검증을 통과한 요청만 Service에 전달합니다. 실패하면 공통 예외 처리기가 400을 반환합니다.
        // var는 오른쪽 값으로 변수 타입을 추론합니다. result의 실제 타입은 SignupService.Result입니다.
        // record의 name() 등은 해당 필드 값을 읽는 메서드입니다.
        var result = signupService.signup(request.name(), request.email(), request.password(),
                request.termsVersion(), request.privacyVersion());
        // HTTP 응답에 공개할 네 가지 값만 옮겨 담습니다.
        var response = new SignupResponse(result.userId(), result.name(), result.email(), result.createdAt());
        // ApiResponse는 JSON 본문을 만들고, ResponseEntity는 실제 HTTP 상태를 201로 설정합니다.
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response));
    }
}
