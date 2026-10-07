package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.User;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** HTTP 경로를 몰라도 호출할 수 있는 회원 접근 가능 여부 검사입니다. */
@Service
public class AccountAccessService {
    private final UserRepository users;

    public AccountAccessService(UserRepository users) {
        this.users = users;
    }

    // JWT의 서명과 만료 검증은 앞선 인증 필터가 담당합니다.
    // 이 서비스는 DB에서 계정의 현재 상태를 확인합니다. 캐시를 쓰지 않아 탈퇴가 즉시 반영됩니다.
    // 읽기 전용 조회이며, 실제 탈퇴 서비스는 쓰기 잠금을 잡은 후 상태를 다시 검사합니다.
    @Transactional(readOnly = true)
    public void requireActiveAccount(UUID userId, boolean withdrawalRequest) {
        var user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (user.getAccountStatus() != User.AccountStatus.ACTIVE) {
            // 탈퇴 재요청은 A10 명세의 409, 다른 보호 API는 기존 규칙인 404입니다.
            throw new BusinessException(withdrawalRequest
                    ? ErrorCode.ACCOUNT_DELETED : ErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
