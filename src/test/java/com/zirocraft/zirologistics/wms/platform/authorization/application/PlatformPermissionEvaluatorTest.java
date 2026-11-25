package com.zirocraft.zirologistics.wms.platform.authorization.application;

import com.zirocraft.zirologistics.wms.platform.security.PlatformAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformPermissionEvaluatorTest {
    private JdbcTemplate jdbcTemplate;
    private PlatformPermissionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        evaluator = new PlatformPermissionEvaluator(jdbcTemplate);
    }

    @Test
    void allowsOnlyExactPersistedPermissionForAuthenticatedPlatformOperator() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq("operator-public-id"), eq("PLATFORM_TENANT_READ"))).thenReturn(1);

        assertThat(evaluator.hasPermission(
                new PlatformAuthenticatedPrincipal("operator-public-id"), "PLATFORM_TENANT_READ")).isTrue();
    }

    @Test
    void deniesWhenNoCurrentPersistedGrantExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq("operator-public-id"), eq("PLATFORM_TENANT_CLOSE"))).thenReturn(0);

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
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq("operator-public-id"), eq("PLATFORM_TENANT_READ")))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThat(evaluator.hasPermission(
                new PlatformAuthenticatedPrincipal("operator-public-id"), "PLATFORM_TENANT_READ")).isFalse();
    }

    @Test
    void queriesPersistedActiveAndUnexpiredGrantStateRatherThanJwtAuthorities() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq("operator-public-id"), eq("PLATFORM_OPERATOR_READ"))).thenReturn(1);

        evaluator.hasPermission(new PlatformAuthenticatedPrincipal("operator-public-id"), "PLATFORM_OPERATOR_READ");

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Integer.class),
                eq("operator-public-id"), eq("PLATFORM_OPERATOR_READ"));
        assertThat(sql.getValue())
                .contains("platform_operator_roles")
                .contains("platform_role_permissions")
                .contains("assignment.revoked_at IS NULL")
                .contains("assignment.expires_at > UTC_TIMESTAMP(6)")
                .contains("operator.status = 'ACTIVE'")
                .contains("role.status = 'ACTIVE'")
                .contains("permission.status = 'ACTIVE'");
    }
}
