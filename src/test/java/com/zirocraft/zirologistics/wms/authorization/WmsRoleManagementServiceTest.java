package com.zirocraft.zirologistics.wms.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.authorization.application.WmsRoleManagementService;
import com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WmsRoleManagementServiceTest {
    private WmsMembershipAccessService accessService;
    private JdbcTemplate jdbc;
    private WmsRoleManagementService service;

    @BeforeEach
    void setUp() {
        accessService = mock(WmsMembershipAccessService.class);
        jdbc = mock(JdbcTemplate.class);
        service = new WmsRoleManagementService(accessService, jdbc, new ObjectMapper());
    }

    @Test
    void deniesRoleReadWhenCurrentMembershipDoesNotHavePermission() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_TENANT_PROFILE_UPDATE")));

        assertThrows(AccessDeniedException.class, () -> service.listRoles(principal));

        verifyNoInteractions(jdbc);
    }

    @Test
    void deniesWhenAuthenticatedPrincipalTenantDoesNotMatchPersistedMembership() {
        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                "user-public", 99L, "another-tenant", 30L, "membership-public",
                Set.of("WMS_ROLE_READ"));
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ROLE_READ")));

        assertThrows(AccessDeniedException.class, () -> service.listRoles(principal));

        verifyNoInteractions(jdbc);
    }

    @Test
    void appliesDeterministicLimitAndOffsetForRequestedRolePage() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ROLE_READ")));

        service.listRoles(principal, 2, 50);

        verify(jdbc).queryForList(contains("LIMIT ? OFFSET ?"), eq(10L), eq(50), eq(100L));
    }

    @Test
    void appliesDeterministicLimitAndOffsetForMembershipRolePage() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_MEMBERSHIP_ROLE_READ")));
        when(jdbc.queryForList(contains("SELECT m.id"), eq(Long.class), eq(10L), eq("target-membership")))
                .thenReturn(List.of(44L));

        service.membershipRoles(principal(), "target-membership", 2, 50);

        verify(jdbc).queryForList(contains("ORDER BY r.role_code LIMIT ? OFFSET ?"),
                eq(String.class), eq(10L), eq(44L), eq(50), eq(100L));
    }

    @Test
    void rejectsNegativePageBeforeExecutingCollectionQuery() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ROLE_READ")));

        assertThrows(IllegalArgumentException.class, () -> service.listRoles(principal, -1, 50));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsPageSizeAboveHardMaximumBeforeExecutingCollectionQuery() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ROLE_READ")));

        assertThrows(IllegalArgumentException.class, () -> service.listRoles(principal, 0, 201));

        verifyNoInteractions(jdbc);
    }

    @Test
    void cannotDelegateInventoryPostingPermissionEvenWhenActorCurrentlyHasIt() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ROLE_PERMISSION_MANAGE", "WMS_INVENTORY_POST")));
        when(jdbc.queryForList(contains("SELECT id FROM tenants"), eq(Long.class), eq(10L)))
                .thenReturn(List.of(10L));
        when(jdbc.queryForList(contains("SELECT id, public_id, role_code"), eq(10L), eq("custom-role")))
                .thenReturn(List.of(Map.of("id", 99L, "public_id", "custom-role",
                        "role_code", "CUSTOM", "is_system_role", 0, "version", 0L)));

        assertThrows(AccessDeniedException.class,
                () -> service.replaceRolePermissions(principal, "custom-role",
                        List.of("WMS_INVENTORY_POST"), 0));

        verify(jdbc, never()).update(contains("DELETE FROM role_permissions"), anyLong());
        verify(jdbc, never()).update(contains("INSERT INTO role_permissions"), any(Object[].class));
    }

    @Test
    void reportsMissingRoleAsResourceNotFound() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ROLE_UPDATE")));
        when(jdbc.queryForList(contains("SELECT id FROM tenants"), eq(Long.class), eq(10L)))
                .thenReturn(List.of(10L));
        when(jdbc.queryForList(contains("SELECT id, public_id, role_code"), eq(10L), eq("missing-role")))
                .thenReturn(List.of());

        assertThrows(WmsResourceNotFoundException.class,
                () -> service.updateRole(principal(), "missing-role", "Updated", null, 0));
    }

    @Test
    void reportsMissingMembershipAsResourceNotFound() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_MEMBERSHIP_ROLE_READ")));
        when(jdbc.queryForList(contains("SELECT m.id"), eq(Long.class), eq(10L), eq("missing-membership")))
                .thenReturn(List.of());

        assertThrows(WmsResourceNotFoundException.class,
                () -> service.membershipRoles(principal(), "missing-membership"));
    }

    private static WmsAuthenticatedPrincipal principal() {
        return new WmsAuthenticatedPrincipal(
                "user-public", 10L, "tenant-public", 30L, "membership-public",
                Set.of("WMS_ROLE_READ"));
    }

    private static WmsMembershipAccess access(Set<String> permissions) {
        return new WmsMembershipAccess(
                1L, "user-public", 10L, "tenant-public", 30L, "membership-public", permissions);
    }
}
