package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.RefreshTokenRepository;
import com.personalab.vectoract.vector_act_was.global.auth.RefreshTokenGenerator;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class LogoutServiceTests {
    @Test
    void missingAndBlankCookiesDoNotHashOrAccessRepository() {
        var tokens = mock(RefreshTokenRepository.class);
        var generator = mock(RefreshTokenGenerator.class);
        var service = new LogoutService(tokens, generator);
        service.logout(null);
        service.logout("");
        service.logout("   ");
        verifyNoInteractions(tokens, generator);
    }
}
