package com.zirocraft.zirologistics.wms.platform.tenancy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.platform.authorization.application.PlatformPermissionEvaluator;
import com.zirocraft.zirologistics.wms.platform.security.PlatformAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantLifecycleAction;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantLifecycleResult;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantLifecycleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformTenantLifecycleApplicationServiceTest {
    private PlatformPermissionEvaluator permissions;
    private WmsTenantLifecycleService lifecycle;
    private JdbcTemplate jdbc;
    private PlatformTenantLifecycleApplicationService service;
    private final String operatorId = UUID.randomUUID().toString();
    private final String tenantId = UUID.randomUUID().toString();
    private final String operationRef = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        permissions = mock(PlatformPermissionEvaluator.class);
        lifecycle = mock(WmsTenantLifecycleService.class);
        jdbc = mock(JdbcTemplate.class);
        service = new PlatformTenantLifecycleApplicationService(
                permissions, lifecycle, jdbc, new ObjectMapper());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                new PlatformAuthenticatedPrincipal(operatorId), null, java.util.List.of());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void checksActionSpecificPermissionAndWritesPlatformAuditForAuthenticatedOperator() {
        when(permissions.hasPermission(any(), eq("PLATFORM_TENANT_SUSPEND"))).thenReturn(true);
        when(lifecycle.transition(WmsTenantLifecycleAction.SUSPEND, tenantId,
                "Verified operational reason for suspension", operationRef, operatorId, null))
                .thenReturn(new WmsTenantLifecycleResult(operationRef, tenantId, "TENANT_A",
                        "ACTIVE", "SUSPENDED", false));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(operatorId))).thenReturn(42L);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        var result = service.transition(new PlatformTenantLifecycleRequest(
                WmsTenantLifecycleAction.SUSPEND, tenantId,
                "Verified operational reason for suspension", operationRef, null));

        assertThat(result.currentStatus()).isEqualTo("SUSPENDED");
        verify(permissions).hasPermission(any(PlatformAuthenticatedPrincipal.class), eq("PLATFORM_TENANT_SUSPEND"));
        verify(lifecycle).transition(WmsTenantLifecycleAction.SUSPEND, tenantId,
                "Verified operational reason for suspension", operationRef, operatorId, null);
        verify(jdbc).update(contains("INSERT INTO platform_audit_events"), any(), eq(42L),
                eq(operationRef), eq("TENANT_SUSPEND"), eq(tenantId),
                eq("Verified operational reason for suspension"), anyString());
    }

    @Test
    void deniesBeforeLifecycleMutationWhenPermissionIsMissing() {
        when(permissions.hasPermission(any(), eq("PLATFORM_TENANT_CLOSE"))).thenReturn(false);

        assertThatThrownBy(() -> service.transition(new PlatformTenantLifecycleRequest(
                WmsTenantLifecycleAction.CLOSE, tenantId,
                "Verified reason to close tenant account", operationRef, "CLOSE:TENANT_A")))
                .isInstanceOf(PlatformTenantLifecycleAccessDeniedException.class);

        verifyNoInteractions(lifecycle);
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsMissingOrNonPlatformAuthentication() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.transition(new PlatformTenantLifecycleRequest(
                WmsTenantLifecycleAction.SUSPEND, tenantId,
                "Verified operational reason for suspension", operationRef, null)))
                .isInstanceOf(PlatformTenantLifecycleAccessDeniedException.class);

        verifyNoInteractions(permissions, lifecycle, jdbc);
    }

    @Test
    void doesNotDuplicatePlatformAuditForAnIdempotentLifecycleRetry() {
        when(permissions.hasPermission(any(), eq("PLATFORM_TENANT_REACTIVATE"))).thenReturn(true);
        when(lifecycle.transition(WmsTenantLifecycleAction.REACTIVATE, tenantId,
                "Verified reason for tenant reactivation", operationRef, operatorId, null))
                .thenReturn(new WmsTenantLifecycleResult(operationRef, tenantId, "TENANT_A",
                        "SUSPENDED", "ACTIVE", true));

        service.transition(new PlatformTenantLifecycleRequest(
                WmsTenantLifecycleAction.REACTIVATE, tenantId,
                "Verified reason for tenant reactivation", operationRef, null));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any());
    }
}
