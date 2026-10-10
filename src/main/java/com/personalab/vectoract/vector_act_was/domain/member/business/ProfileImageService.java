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
 *
 * <p>핵심 아이디어: DB와 객체 저장소(S3)는 하나의 트랜잭션으로 묶을 수 없습니다. 그래서
 * "DB가 확정된 뒤에만 옛 객체를 지우고, DB가 롤백되면 새로 올린 객체를 지운다"는 보상 작업을
 * 트랜잭션 이벤트({@link ProfileImageCleanupListener})로 연결해, 어떤 경우에도 DB가 가리키는 객체는
 * 남아 있도록 합니다. 최악의 경우에도 "참조 없는 객체가 남는" 쪽으로만 실패합니다(데이터 손실 없음).
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

    /** A15: 이미지를 검증·정제해 저장하고 회원의 프로필 이미지 key를 새 값으로 바꿉니다. */
    @Transactional
    public MeService.Result replace(UUID userId, byte[] content) {
        // 디코딩으로 JPEG/PNG를 확인하고 메타데이터(EXIF 등)를 제거합니다. 실패하면 DB·저장소를 건드리지 않습니다.
        var processed = processor.process(content);
        // 회원 ID를 key에 포함해 소유자를 추적할 수 있게 하고, UUID로 교체 때 key가 항상 달라지게 합니다.
        // (key가 매번 다르면 "이전 객체"와 "새 객체"가 겹치지 않아 안전하게 따로 지울 수 있습니다.)
        String key = "profile-images/" + userId + "/" + UUID.randomUUID() + "." + processed.extension();
        storage.put(key, processed.content(), processed.contentType());
        // 이후 단계에서 실패해 롤백되면 방금 저장한 객체를 삭제합니다.
        events.publishEvent(new ProfileImageUploaded(key));

        // 회원 행을 잠근 뒤 key를 바꿉니다. 같은 회원의 동시 교체·삭제·탈퇴가 서로의 이전 key를 잘못 읽지 않게 합니다.
        var user = lockActive(userId);
        String previous = user.getProfileImageKey();
        user.changeProfileImage(key, OffsetDateTime.now(ZoneOffset.UTC));
        users.flush();
        // 이전 객체 삭제는 커밋 이후에 실행되도록 이벤트로만 예약합니다(여기서 바로 지우지 않습니다).
        if (previous != null) events.publishEvent(new ProfileImageObsolete(previous));
        return MeService.toResult(user, storage);
    }

    /** A16: 프로필 이미지를 지웁니다. 이미지가 없어도 정상 응답입니다. */
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

    /** 회원 행을 잠그고 ACTIVE인지 확인합니다. 탈퇴 등 ACTIVE가 아니면 존재하지 않는 것처럼 404로 응답합니다. */
    private User lockActive(UUID userId) {
        return users.lockById(userId)
                .filter(found -> found.getAccountStatus() == User.AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
