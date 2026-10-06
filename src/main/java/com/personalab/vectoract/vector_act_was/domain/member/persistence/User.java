package com.personalab.vectoract.vector_act_was.domain.member.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
// 동시에 실행되는 이름 수정이 읽어 둔 이전 비밀번호 해시를 덮어쓰지 않게 합니다.
// @DynamicUpdate는 실제로 바뀐 필드만 UPDATE SQL에 포함하게 합니다.
// 예: A07이 이름만 바꾸면 password_hash를 SQL에 넣지 않아 A08의 새 해시를 보존합니다.
@DynamicUpdate
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 30)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    private AccountStatus accountStatus;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime deletedAt;

    @Column(name = "purge_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime purgeAt;

    private User(String email, String passwordHash, String name) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.accountStatus = AccountStatus.ACTIVE;
    }

    /** Creates an active user from an already encoded password hash. */
    public static User create(String email, String passwordHash, String name) {
        return new User(email, passwordHash, name);
    }

    public void changeName(String name) {
        this.name = name;
    }

    // 이미 Service에서 BCrypt로 변환한 해시만 전달받습니다. 평문을 넣는 메서드가 아닙니다.
    // 이 시점에는 Java 객체의 값만 변경됩니다. 트랜잭션 안의 JPA 변경 감지가 DB UPDATE를 수행합니다.
    public void changePasswordHash(String encodedPassword) {
        this.passwordHash = encodedPassword;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (accountStatus == null) accountStatus = AccountStatus.ACTIVE;
    }

    // JPA가 기존 행을 갱신하기 전에 호출하는 생명주기 콜백입니다.
    // A08에서 passwordHash가 바뀌면 이 메서드가 수정 시각도 갱신합니다.
    // 메서드를 직접 부르지 않아도 되지만, JPA를 우회한 직접 SQL에는 자동 적용되지 않습니다.
    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public enum AccountStatus {
        ACTIVE, WITHDRAWN
    }
}
