package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;

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
    void normalizesEmailBeforeExistenceCheck() {
        UserRepository repository = mock(UserRepository.class);
        WmsUserLookupService service = new WmsUserLookupService(repository);
        when(repository.existsByEmail("user@example.com")).thenReturn(true);

        assertThat(service.existsByEmail(" USER@Example.COM ")).isTrue();
        verify(repository).existsByEmail("user@example.com");
        verifyNoMoreInteractions(repository);
    }
}
