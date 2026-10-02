package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.User;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.MeResponse;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class MeService {
    private final UserRepository users;

    public MeService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(UUID userId) {
        var user = users.findById(userId)
                .filter(found -> found.getAccountStatus() == User.AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 엔티티 전체 대신 공개할 네 가지 필드만 반환합니다.
        return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
    }
}
