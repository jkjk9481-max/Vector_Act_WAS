package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberPurgeServiceTests {
    @Test
    void missingStorageAdapterNeverDeletesDatabase() {
        var users = mock(UserRepository.class);
        var refresh = mock(RefreshTokenRepository.class);
        var tokens = mock(AuthOneTimeTokenRepository.class);
        var consents = mock(UserConsentRepository.class);
        var user = User.create("test@example.com", "hash", "회원");
        user.withdraw(OffsetDateTime.now().minusDays(8));
        var id = UUID.randomUUID();
        when(users.lockById(id)).thenReturn(Optional.of(user));
        var emptyBeans = new StaticListableBeanFactory().getBeanProvider(MemberDataEraser.class);
        var service = new MemberPurgeService(users, refresh, tokens, consents, emptyBeans);
        assertThatThrownBy(() -> service.purge(id)).isInstanceOf(IllegalStateException.class);
        verify(users, never()).delete(any(User.class));
        verifyNoInteractions(refresh, tokens, consents);
    }

    @Test
    void failedMemberDoesNotStopOtherMembers() {
        var users = mock(UserRepository.class);
        var service = mock(MemberPurgeService.class);
        var failed = UUID.randomUUID();
        var succeeded = UUID.randomUUID();
        when(users.findPurgeCandidates(any())).thenReturn(List.of(failed, succeeded));
        when(service.purge(failed)).thenThrow(new IllegalStateException("failure"));
        when(service.purge(succeeded)).thenReturn(true);
        new MemberPurgeScheduler(users, service).purgeDueUsers();
        verify(service).purge(succeeded);
    }
}
