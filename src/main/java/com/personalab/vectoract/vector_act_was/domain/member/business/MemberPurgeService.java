package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;

/**
 * 7일 보관 기간이 끝난 회원의 영구 삭제를 담당합니다.
 * Scheduler는 실행 시점과 반복을, 이 Service는 삭제 조건·순서·트랜잭션을 담당합니다.
 * Repository는 실제 DB 조회와 삭제를 수행합니다.
 */
@Service
public class MemberPurgeService {
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AuthOneTimeTokenRepository oneTimeTokens;
    private final UserConsentRepository consents;
    // ObjectProvider는 필요할 때 Spring Bean(관리 객체)을 찾아주는 도구입니다.
    // 구현체가 없어도 탈퇴 API 자체는 사용할 수 있게 하고, 영구 삭제 시점에만 연결 여부를 검사합니다.
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

    // 스케줄러가 Repository에 직접 접근하지 않도록 대상 조회도 이 서비스가 담당합니다.
    // 조회가 끝나면 읽기 트랜잭션도 끝납니다. 전체 배치를 하나의 쓰기 트랜잭션으로 묶지 않습니다.
    @Transactional(readOnly = true)
    public java.util.List<UUID> findDueUserIds() {
        return users.findPurgeCandidates(OffsetDateTime.now(ZoneOffset.UTC));
    }

    // 스케줄러와 별도 Bean이어야 Spring의 트랜잭션이 실제로 적용됩니다.
    // 회원 한 명마다 커밋하므로 한 명의 삭제 실패가 다른 회원의 처리를 취소하지 않습니다.
    @Transactional
    public boolean purge(UUID userId) {
        // 1. 대상 조회 후 다른 서버가 먼저 삭제했을 수 있으므로 잠금을 잡고 다시 확인합니다.
        // 잠금은 트랜잭션이 끝날 때 해제됩니다. 같은 회원을 두 작업이 동시에 삭제하지 못하게 합니다.
        var user = users.lockById(userId).orElse(null);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (user == null || user.getAccountStatus() != User.AccountStatus.WITHDRAWN
                || user.getPurgeAt() == null || user.getPurgeAt().isAfter(now)) return false;

        // false는 실패가 아니라 '지금 삭제할 대상이 아님'입니다. 이미 없거나, ACTIVE이거나,
        // 예정 시각이 없거나, 아직 7일이 지나지 않은 경우에는 삭제 작업을 하지 않습니다.
        // 2. 저장소 연동이 없는데 성공한 것으로 처리하면 영상만 영구히 남을 수 있습니다.
        // 구현체가 없으면 실패시켜 users와 삭제 예정 시각을 그대로 보존합니다.
        var dataEraser = eraser.getIfAvailable();
        if (dataEraser == null) throw new IllegalStateException("Member data eraser is not configured");
        // 현재 실제 구현체가 없는 상태에서는 바로 위에서 중단되므로 아래 DB 삭제에 도달하지 않습니다.
        // 3. 반드시 외부 저장소부터 삭제합니다. 일부 삭제 후 실패해도 다음 실행에서 다시 시도합니다.
        // 외부 저장소는 DB 트랜잭션으로 되돌릴 수 없습니다. 이미 지운 파일의 재삭제를 허용해야 합니다.
        dataEraser.deleteExternalData(userId);
        // 4. 외부 삭제가 모두 성공한 다음 영상·분석 DB 행을 지웁니다. 구현체는 같은 트랜잭션에 참여합니다.
        dataEraser.deleteDatabaseData(userId);
        // 5. 토큰과 약관 동의는 users를 참조하는 자식 데이터입니다. 자식을 먼저 삭제한 뒤 부모를 삭제합니다.
        refreshTokens.deleteByUserId(userId);
        oneTimeTokens.deleteByUserId(userId);
        consents.deleteByUserId(userId);
        users.delete(user);
        // flush는 삭제 SQL을 DB로 보내는 단계이며 최종 확정(커밋)은 아닙니다.
        // 자식/회원 삭제 중 오류가 나면 DB 삭제 전체가 롤백되어 다음 주기에 재시도할 수 있습니다.
        users.flush();
        // 커밋 실패까지 포함한 최종 성공 여부는 호출자가 반환 후 기록합니다.
        return true;
    }
}
