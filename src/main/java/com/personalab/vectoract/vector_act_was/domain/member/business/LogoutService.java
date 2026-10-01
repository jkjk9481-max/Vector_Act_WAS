package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.RefreshTokenRepository;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class LogoutService {
    private final RefreshTokenRepository tokens;
    private final RefreshTokenGenerator generator;

    public LogoutService(RefreshTokenRepository tokens, RefreshTokenGenerator generator) {
        this.tokens = tokens;
        this.generator = generator;
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        String hash = generator.hash(rawToken);
        // Use A04's stable owner lock before reading the family so rotation cannot escape logout.
        if (tokens.lockOwnerByTokenHash(hash).isEmpty()) return;
        tokens.findByTokenHash(hash).ifPresent(token -> {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            tokens.findByFamilyIdAndRevokedAtIsNull(token.getFamilyId())
                    .forEach(active -> active.revoke(now));
            tokens.flush();
        });
    }
}
