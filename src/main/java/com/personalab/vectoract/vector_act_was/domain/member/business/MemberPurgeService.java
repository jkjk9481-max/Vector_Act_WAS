package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;

@Service
public class MemberPurgeService {
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AuthOneTimeTokenRepository oneTimeTokens;
    private final UserConsentRepository consents;
    private final ObjectProvider<MemberDataEraser> eraser;

    public MemberPurgeService(UserRepository users, RefreshTokenRepository refreshTokens,
            AuthOneTimeTokenRepository oneTimeTokens, UserConsentRepository consents,
            ObjectProvider<MemberDataEraser> eraser) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.oneTimeTokens = oneTimeTokens;
        this.consents = consents;
        this.eraser = eraser;
    }

    // 스케줄러와 별도 Bean이어야 Spring의 트랜잭션이 실제로 적용됩니다.
    // 회원 한 명마다 커밋하므로 한 명의 삭제 실패가 다른 회원의 처리를 취소하지 않습니다.
    @Transactional
    public boolean purge(UUID userId) {
        var user = users.lockById(userId).orElse(null);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (user == null || user.getAccountStatus() != User.AccountStatus.WITHDRAWN
                || user.getPurgeAt() == null || user.getPurgeAt().isAfter(now)) return false;

        // 저장소 연동이 없는데 성공한 것으로 처리하면 영상만 영구히 남을 수 있습니다.
        // 구현체가 없으면 실패시켜 users와 삭제 예정 시각을 그대로 보존합니다.
        var dataEraser = eraser.getIfAvailable();
        if (dataEraser == null) throw new IllegalStateException("Member data eraser is not configured");
        // 반드시 외부 저장소부터 삭제합니다. 일부 삭제 후 실패해도 다음 실행에서 다시 시도합니다.
        dataEraser.deleteExternalData(userId);
        dataEraser.deleteDatabaseData(userId);
        refreshTokens.deleteByUserId(userId);
        oneTimeTokens.deleteByUserId(userId);
        consents.deleteByUserId(userId);
        users.delete(user);
        users.flush();
        // 커밋 실패까지 포함한 최종 성공 여부는 호출자가 반환 후 기록합니다.
        return true;
    }
}
