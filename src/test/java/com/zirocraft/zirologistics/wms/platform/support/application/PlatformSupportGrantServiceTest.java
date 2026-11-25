package com.zirocraft.zirologistics.wms.platform.support.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.platform.authorization.application.PlatformPermissionEvaluator;
import com.zirocraft.zirologistics.wms.platform.security.PlatformAuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformSupportGrantServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private PlatformPermissionEvaluator permissions;
    private JdbcTemplate jdbc;
    private PlatformSupportGrantService service;
    private final String actor = UUID.randomUUID().toString();
    private final String tenant = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        permissions = mock(PlatformPermissionEvaluator.class);
        jdbc = mock(JdbcTemplate.class);
        service = new PlatformSupportGrantService(
                permissions, jdbc, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new PlatformAuthenticatedPrincipal(actor, NOW), null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsTenantBoundPurposeBoundTimeLimitedRequestWithOnlyAllowlistedScopes() {
        when(permissions.hasPermission(any(), eq("PLATFORM_SUPPORT_GRANT_CREATE"))).thenReturn(true);
        when(jdbc.queryForObject(contains("SELECT id FROM platform_operators"), eq(Long.class), eq(actor)))
                .thenReturn(11L);
        when(jdbc.queryForObject(contains("SELECT id FROM tenants"), eq(Long.class), eq(tenant)))
                .thenReturn(22L);
        when(jdbc.update(contains("INSERT INTO platform_support_grants"), any(), eq(22L), eq(11L),
                any(), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.queryForObject(contains("SELECT id FROM platform_support_grants"), eq(Long.class), any()))
                .thenReturn(33L);
        when(jdbc.update(contains("INSERT INTO platform_support_grant_scopes"), eq(33L), anyString()))
                .thenReturn(1);
        when(jdbc.update(contains("INSERT INTO platform_audit_events"), any(), eq(11L),
                any(), eq("SUPPORT_GRANT_REQUESTED"), any(), anyString(), anyString())).thenReturn(1);

        String grantId = service.requestGrant(new PlatformSupportGrantRequest(
                tenant, "Investigate customer integration health issue", UUID.randomUUID().toString(),
                NOW.plusSeconds(60), NOW.plusSeconds(3600),
                Set.of(PlatformSupportScope.INTEGRATION_DIAGNOSTICS_READ,
                        PlatformSupportScope.TENANT_METADATA_READ)));

        assertThat(UUID.fromString(grantId)).isNotNull();
        verify(jdbc, times(2)).update(contains("INSERT INTO platform_support_grant_scopes"), eq(33L), anyString());
        verify(jdbc).update(contains("INSERT INTO platform_audit_events"), any(), eq(11L),
                any(), eq("SUPPORT_GRANT_REQUESTED"), eq(grantId),
                eq("Investigate customer integration health issue"), anyString());
    }

    @Test
    void rejectsRequestWhenPermissionIsMissing() {
        when(permissions.hasPermission(any(), eq("PLATFORM_SUPPORT_GRANT_CREATE"))).thenReturn(false);

        assertThatThrownBy(() -> service.requestGrant(new PlatformSupportGrantRequest(
                tenant, "Investigate customer integration health issue", UUID.randomUUID().toString(),
                NOW.plusSeconds(60), NOW.plusSeconds(3600),
                Set.of(PlatformSupportScope.TENANT_METADATA_READ))))
                .isInstanceOf(PlatformSupportGrantRejectedException.class);

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsGrantWindowLongerThanFourHours() {
        when(permissions.hasPermission(any(), eq("PLATFORM_SUPPORT_GRANT_CREATE"))).thenReturn(true);

        assertThatThrownBy(() -> service.requestGrant(new PlatformSupportGrantRequest(
                tenant, "Investigate customer integration health issue", UUID.randomUUID().toString(),
                NOW.plusSeconds(60), NOW.plusSeconds(5 * 3600),
                Set.of(PlatformSupportScope.TENANT_METADATA_READ))))
                .isInstanceOf(PlatformSupportGrantRejectedException.class);

        verifyNoInteractions(jdbc);
    }

    @Test
    void requiresDifferentAuthenticatedApproverForGrantDecision() {
        when(permissions.hasPermission(any(), eq("PLATFORM_SUPPORT_GRANT_APPROVE"))).thenReturn(true);
        when(jdbc.queryForObject(contains("SELECT id FROM platform_operators"), eq(Long.class), eq(actor)))
                .thenReturn(11L);
        when(jdbc.queryForList(contains("FROM platform_support_grants"), any()))
                .thenReturn(List.of(Map.of("id", 33L, "tenant_id", 22L,
                        "requested_by_operator_id", 11L, "status", "REQUESTED", "tenant_status", "ACTIVE")));

        assertThatThrownBy(() -> service.approveGrant(
                UUID.randomUUID().toString(), "Approved after independent access review",
                UUID.randomUUID().toString()))
                .isInstanceOf(PlatformSupportGrantRejectedException.class);

        verify(jdbc, never()).update(contains("UPDATE platform_support_grants"), any(), any());
    }
}
