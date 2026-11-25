package com.zirocraft.zirologistics.wms.platform.authorization.application;

import com.zirocraft.zirologistics.wms.platform.security.PlatformAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformPermissionEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private JdbcTemplate jdbcTemplate;
    private PlatformPermissionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        evaluator = new PlatformPermissionEvaluator(jdbcTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void allowsExactLowRiskPersistedPermissionWithoutElevatedMfaWindow() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("operator-public-id"), eq("PLATFORM_TENANT_READ"))).thenReturn("LOW");

        assertThat(evaluator.hasPermission(
                new PlatformAuthenticatedPrincipal("operator-public-id"), "PLATFORM_TENANT_READ")).isTrue();
    }

    @Test
    void deniesWhenNoCurrentPersistedGrantExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("operator-public-id"), eq("PLATFORM_TENANT_CLOSE"))).thenReturn(null);

        assertThat(evaluator.hasPermission(
                new PlatformAuthenticatedPrincipal("operator-public-id"), "PLATFORM_TENANT_CLOSE")).isFalse();
    }

    @Test
    void deniesInvalidInputWithoutQueryingAuthorizationStore() {
        assertThat(evaluator.hasPermission(null, "PLATFORM_TENANT_READ")).isFalse();
        assertThat(evaluator.hasPermission(
                new PlatformAuthenticatedPrincipal("operator-public-id"), null)).isFalse();
        assertThat(evaluator.hasPermission(
                new PlatformAuthenticatedPrincipal("operator-public-id"), " ")).isFalse();
        assertThat(evaluator.hasPermission(
                new PlatformAuthenticatedPrincipal("operator-public-id"), " PLATFORM_TENANT_READ")).isFalse();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void deniesWhenAuthorizationStoreIsUnavailable() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("operator-public-id"), eq("PLATFORM_TENANT_READ")))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThat(evaluator.hasPermission(
                new PlatformAuthenticatedPrincipal("operator-public-id"), "PLATFORM_TENANT_READ")).isFalse();
    }

    @Test
    void requiresMfaWithinFiveMinutesForHighAndCriticalPermissions() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("operator-public-id"), eq("PLATFORM_TENANT_CLOSE"))).thenReturn("CRITICAL");
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("operator-public-id"), eq("PLATFORM_TENANT_SUSPEND"))).thenReturn("HIGH");

        var fresh = new PlatformAuthenticatedPrincipal("operator-public-id", NOW.minusSeconds(299));
        var stale = new PlatformAuthenticatedPrincipal("operator-public-id", NOW.minusSeconds(301));
        var future = new PlatformAuthenticatedPrincipal("operator-public-id", NOW.plusSeconds(1));
        var missing = new PlatformAuthenticatedPrincipal("operator-public-id");

        assertThat(evaluator.hasPermission(fresh, "PLATFORM_TENANT_CLOSE")).isTrue();
        assertThat(evaluator.hasPermission(fresh, "PLATFORM_TENANT_SUSPEND")).isTrue();
        assertThat(evaluator.hasPermission(stale, "PLATFORM_TENANT_CLOSE")).isFalse();
        assertThat(evaluator.hasPermission(future, "PLATFORM_TENANT_CLOSE")).isFalse();
        assertThat(evaluator.hasPermission(missing, "PLATFORM_TENANT_CLOSE")).isFalse();
    }

    @Test
    void resolvesRiskFromCurrentPersistedGrantAndHonorsActiveState() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("operator-public-id"), eq("PLATFORM_OPERATOR_READ"))).thenReturn("LOW");

        evaluator.hasPermission(new PlatformAuthenticatedPrincipal("operator-public-id"), "PLATFORM_OPERATOR_READ");

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(String.class),
                eq("operator-public-id"), eq("PLATFORM_OPERATOR_READ"));
        assertThat(sql.getValue())
                .contains("platform_operator_roles")
                .contains("platform_role_permissions")
                .contains("assignment.revoked_at IS NULL")
                .contains("assignment.expires_at > UTC_TIMESTAMP(6)")
                .contains("operator.status = 'ACTIVE'")
                .contains("role.status = 'ACTIVE'")
                .contains("permission.status = 'ACTIVE'")
                .contains("permission.risk_level = 'CRITICAL'")
                .contains("permission.risk_level = 'HIGH'");
    }
}
