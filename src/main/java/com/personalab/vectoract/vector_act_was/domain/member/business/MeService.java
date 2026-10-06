package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.User;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.OffsetDateTime;

// @Service는 회원 조회 업무를 담당하는 이 클래스를 Spring 관리 객체로 등록합니다.
@Service
public class MeService {
    private final UserRepository users;

    // Repository는 DB 접근 담당입니다. Spring이 구현한 UserRepository를 생성자로 전달받습니다.
    public MeService(UserRepository users) {
        this.users = users;
    }

    // 조회 작업을 하나의 트랜잭션으로 묶습니다. readOnly는 읽기 전용이라는 힌트이며 쓰기 차단 장치는 아닙니다.
    @Transactional(readOnly = true)
    public Result getMe(UUID userId) {
        // findById는 기본 키(UUID)로 조회하고 Optional<User>를 반환합니다.
        // Optional은 조회 결과가 있을 수도, 없을 수도 있음을 표현합니다. var는 컴파일러가 타입을 추론합니다.
        var user = users.findById(userId)
                // 회원이 있어도 ACTIVE 상태만 남깁니다. 탈퇴 회원은 결과가 없는 것으로 처리합니다.
                .filter(found -> found.getAccountStatus() == User.AccountStatus.ACTIVE)
                // 결과가 비어 있으면 예외를 던집니다. 공통 예외 처리기가 404 RESOURCE_NOT_FOUND를 응답합니다.
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // business 계층은 HTTP 응답 DTO를 모르며 조회 결과만 반환합니다.
        // 비밀번호 해시 등 불필요한 엔티티 정보는 결과에 포함하지 않습니다.
        return new Result(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
    }

    // 조회와 변경을 하나의 쓰기 트랜잭션으로 처리합니다.
    // 관리 중인 엔티티의 변경 감지로 저장되며 @PreUpdate가 updated_at을 갱신합니다.
    @Transactional
    public Result updateMe(UUID userId, String name) {
        var user = users.findById(userId)
                .filter(found -> found.getAccountStatus() == User.AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        user.changeName(name);
        return new Result(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
    }

    public record Result(UUID userId, String name, String email, OffsetDateTime createdAt) {}
}
