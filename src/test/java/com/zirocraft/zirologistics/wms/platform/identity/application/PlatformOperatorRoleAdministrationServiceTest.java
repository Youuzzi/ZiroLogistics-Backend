package com.zirocraft.zirologistics.wms.platform.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.platform.authorization.application.PlatformPermissionEvaluator;
import com.zirocraft.zirologistics.wms.platform.security.PlatformAuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformOperatorRoleAdministrationServiceTest {
    private PlatformPermissionEvaluator permissions;
    private JdbcTemplate jdbc;
    private PlatformOperatorRoleAdministrationService service;
    private final String actor = UUID.randomUUID().toString();
    private final String target = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        permissions = mock(PlatformPermissionEvaluator.class);
        jdbc = mock(JdbcTemplate.class);
        service = new PlatformOperatorRoleAdministrationService(permissions, jdbc, new ObjectMapper());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new PlatformAuthenticatedPrincipal(actor), null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requiresExactRoleAssignmentPermissionBeforeReadingOrMutatingTarget() {
        when(permissions.hasPermission(any(), eq("PLATFORM_OPERATOR_ROLE_ASSIGN"))).thenReturn(false);

        assertThatThrownBy(() -> service.assignRole(target, "PLATFORM_SUPPORT",
                "Approved role assignment after access review", UUID.randomUUID().toString(), null))
                .isInstanceOf(PlatformOperatorRoleAdministrationRejectedException.class);

        verifyNoInteractions(jdbc);
    }

    @Test
    void preventsSelfAssignmentEvenWhenPermissionExists() {
        when(permissions.hasPermission(any(), eq("PLATFORM_OPERATOR_ROLE_ASSIGN"))).thenReturn(true);

        assertThatThrownBy(() -> service.assignRole(actor, "PLATFORM_SUPPORT",
                "Approved role assignment after access review", UUID.randomUUID().toString(), null))
                .isInstanceOf(PlatformOperatorRoleAdministrationRejectedException.class);

        verifyNoInteractions(jdbc);
    }

    @Test
    void preventsRoutineAssignmentOfPlatformOwnerRole() {
        when(permissions.hasPermission(any(), eq("PLATFORM_OPERATOR_ROLE_ASSIGN"))).thenReturn(true);

        assertThatThrownBy(() -> service.assignRole(target, "PLATFORM_OWNER",
                "Approved role assignment after access review", UUID.randomUUID().toString(), null))
                .isInstanceOf(PlatformOperatorRoleAdministrationRejectedException.class);

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsMissingPlatformAuthentication() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.revokeRole(target, "PLATFORM_SUPPORT",
                "Approved role revocation after access review", UUID.randomUUID().toString()))
                .isInstanceOf(PlatformOperatorRoleAdministrationRejectedException.class);

        verifyNoInteractions(permissions, jdbc);
    }
}
