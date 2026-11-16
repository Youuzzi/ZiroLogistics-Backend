package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WmsUserLookupServiceTest {

    @Test
    void normalizesEmailBeforeRepositoryLookup() {
        UserRepository repository = mock(UserRepository.class);
        WmsUserLookupService service = new WmsUserLookupService(repository);
        when(repository.findByEmail("user@example.com")).thenReturn(Optional.empty());

        assertThat(service.findByEmail(" USER@Example.COM ")).isEmpty();
        verify(repository).findByEmail("user@example.com");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void recordsSuccessfulLoginOnlyWhenTheDatabaseConfirmsTheUserIsStillActive() {
        UserRepository repository = mock(UserRepository.class);
        WmsUserLookupService service = new WmsUserLookupService(repository);
        when(repository.recordSuccessfulLogin(17L)).thenReturn(1);

        assertThat(service.recordSuccessfulLogin(17L)).isTrue();
        verify(repository).recordSuccessfulLogin(17L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void refusesLoginMetadataWhenTheUserIsNoLongerActive() {
        UserRepository repository = mock(UserRepository.class);
        WmsUserLookupService service = new WmsUserLookupService(repository);
        when(repository.recordSuccessfulLogin(17L)).thenReturn(0);

        assertThat(service.recordSuccessfulLogin(17L)).isFalse();
        verify(repository).recordSuccessfulLogin(17L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsInvalidUserIdBeforeDatabaseAccess() {
        UserRepository repository = mock(UserRepository.class);
        WmsUserLookupService service = new WmsUserLookupService(repository);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.recordSuccessfulLogin(0L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void normalizesEmailBeforeExistenceCheck() {
        UserRepository repository = mock(UserRepository.class);
        WmsUserLookupService service = new WmsUserLookupService(repository);
        when(repository.existsByEmail("user@example.com")).thenReturn(true);

        assertThat(service.existsByEmail(" USER@Example.COM ")).isTrue();
        verify(repository).existsByEmail("user@example.com");
        verifyNoMoreInteractions(repository);
    }
}
