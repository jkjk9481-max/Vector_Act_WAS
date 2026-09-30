package com.personalab.vectoract.vector_act_was.domain.member.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "user_consents", uniqueConstraints = @UniqueConstraint(
        name = "uk_user_consent_type_version",
        columnNames = {"user_id", "consent_type", "version"}
))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 20)
    private ConsentType consentType;

    @Column(nullable = false, length = 32)
    private String version;

    @Column(name = "accepted_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime acceptedAt;

    private UserConsent(User user, ConsentType consentType, String version) {
        this.user = user;
        this.consentType = consentType;
        this.version = version;
        this.acceptedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public static UserConsent create(User user, ConsentType consentType, String version) {
        return new UserConsent(user, consentType, version);
    }

    @PrePersist
    void onCreate() {
        if (acceptedAt == null) acceptedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public enum ConsentType {
        TERMS, PRIVACY
    }
}
