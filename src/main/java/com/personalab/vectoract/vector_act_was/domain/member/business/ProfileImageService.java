package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A15 프로필 이미지 등록·변경, A16 삭제.
 * 저장소는 DB 트랜잭션으로 되돌릴 수 없으므로 순서를 다음과 같이 둡니다.
 * 새 객체 저장 → 회원 행 잠금 후 key 변경 → 커밋 후 이전 객체 삭제, 롤백 시 새 객체 삭제.
 */
@Service
public class ProfileImageService {
    private final UserRepository users;
    private final ProfileImageProcessor processor;
    private final ProfileImageStorage storage;
    private final ApplicationEventPublisher events;

    public ProfileImageService(UserRepository users, ProfileImageProcessor processor,
                               ProfileImageStorage storage, ApplicationEventPublisher events) {
        this.users = users;
        this.processor = processor;
        this.storage = storage;
        this.events = events;
    }

    @Transactional
    public MeService.Result replace(UUID userId, byte[] content) {
        var processed = processor.process(content);
        // 회원 ID를 key에 포함해 소유자를 추적할 수 있게 하고, UUID로 교체 때 key가 항상 달라지게 합니다.
        String key = "profile-images/" + userId + "/" + UUID.randomUUID() + "." + processed.extension();
        storage.put(key, processed.content(), processed.contentType());
        // 이후 단계에서 실패해 롤백되면 방금 저장한 객체를 삭제합니다.
        events.publishEvent(new ProfileImageUploaded(key));

        var user = lockActive(userId);
        String previous = user.getProfileImageKey();
        user.changeProfileImage(key, OffsetDateTime.now(ZoneOffset.UTC));
        users.flush();
        if (previous != null) events.publishEvent(new ProfileImageObsolete(previous));
        return MeService.toResult(user, storage);
    }

    @Transactional
    public MeService.Result remove(UUID userId) {
        var user = lockActive(userId);
        String previous = user.getProfileImageKey();
        // 이미지가 없어도 정상 응답입니다.
        if (previous != null) {
            user.clearProfileImage(OffsetDateTime.now(ZoneOffset.UTC));
            users.flush();
            events.publishEvent(new ProfileImageObsolete(previous));
        }
        return MeService.toResult(user, storage);
    }

    private User lockActive(UUID userId) {
        return users.lockById(userId)
                .filter(found -> found.getAccountStatus() == User.AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
