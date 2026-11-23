package com.zirocraft.zirologistics.wms.security.application;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsJwtAuthenticationConverterTest {

    private WmsMembershipAccessService membershipAccessService;
    private WmsJwtAuthenticationConverter converter;

    @BeforeEach
    void setUp() {
        membershipAccessService = mock(WmsMembershipAccessService.class);
        converter = new WmsJwtAuthenticationConverter(membershipAccessService);
    }

    @Test
    void resolvesTenantAndCurrentPermissionsFromDatabase() {
        WmsMembershipAccess access = new WmsMembershipAccess(
                11L, "user-public-id", 22L, "tenant-public-id",
                33L, "membership-public-id",
                Set.of("inventory.balance.read", "outbound.shipment.create"));
        when(membershipAccessService.resolve("user-public-id", "membership-public-id"))
                .thenReturn(access);

        Authentication authentication = converter.convert(accessToken(
                "user-public-id", "membership-public-id"));

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("user-public-id");
        assertThat(authentication.getPrincipal()).isInstanceOf(WmsAuthenticatedPrincipal.class);

        WmsAuthenticatedPrincipal principal = (WmsAuthenticatedPrincipal) authentication.getPrincipal();
        assertThat(principal.tenantId()).isEqualTo(22L);
        assertThat(principal.tenantPublicId()).isEqualTo("tenant-public-id");
        assertThat(principal.membershipId()).isEqualTo(33L);
        assertThat(principal.membershipPublicId()).isEqualTo("membership-public-id");
        assertThat(principal.permissionCodes())
                .containsExactlyInAnyOrder("inventory.balance.read", "outbound.shipment.create");
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder(
                        "PERM_inventory.balance.read",
                        "PERM_outbound.shipment.create");
        verify(membershipAccessService).resolve("user-public-id", "membership-public-id");
    }

    @Test
    void rejectsAccessTokenWithoutSubjectOrMembershipClaim() {
        Jwt missingSubject = accessToken(null, "membership-public-id");
        Jwt missingMembership = accessToken("user-public-id", null);

        assertThatThrownBy(() -> converter.convert(missingSubject))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(missingMembership))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void rejectsTokensIssuedForOtherPurposesBeforeResolvingMembership() {
        Jwt tenantSelection = accessToken("user-public-id", "membership-public-id", "tenant_selection");
        Jwt passwordReset = accessToken("user-public-id", "membership-public-id", "password_reset");
        Jwt invitation = accessToken("user-public-id", "membership-public-id", "invitation");

        assertThatThrownBy(() -> converter.convert(tenantSelection)).isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(passwordReset)).isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(invitation)).isInstanceOf(OAuth2AuthenticationException.class);
        org.mockito.Mockito.verifyNoInteractions(membershipAccessService);
    }

    @Test
    void rejectsTokenWhenPurposeClaimIsMissing() {
        Instant now = Instant.now();
        Jwt missingPurpose = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject("user-public-id").issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim("mid", "membership-public-id").build();
        assertThatThrownBy(() -> converter.convert(missingPurpose)).isInstanceOf(OAuth2AuthenticationException.class);
        org.mockito.Mockito.verifyNoInteractions(membershipAccessService);
    }

    @Test
    void rejectsMembershipThatIsNoLongerActiveOrDoesNotBelongToUser() {
        when(membershipAccessService.resolve("user-public-id", "membership-public-id"))
                .thenThrow(new WmsMembershipAccessDeniedException());

        assertThatThrownBy(() -> converter.convert(accessToken(
                "user-public-id", "membership-public-id")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("Invalid bearer token");
    }

    @Test
    void rejectsResolvedIdentityThatDoesNotMatchTokenSubjectAndMembership() {
        when(membershipAccessService.resolve("user-public-id", "membership-public-id"))
                .thenReturn(new WmsMembershipAccess(
                        11L, "different-user", 22L, "tenant-public-id",
                        33L, "different-membership", Set.of()));

        assertThatThrownBy(() -> converter.convert(accessToken(
                "user-public-id", "membership-public-id")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void rejectsNullMembershipResolution() {
        when(membershipAccessService.resolve("user-public-id", "membership-public-id"))
                .thenReturn(null);

        assertThatThrownBy(() -> converter.convert(accessToken(
                "user-public-id", "membership-public-id")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("Invalid bearer token");
        verify(membershipAccessService).resolve("user-public-id", "membership-public-id");
    }


    @Test
    void rejectsNonStringMembershipAndPurposeClaimsBeforeResolvingMembership() {
        Instant now = Instant.now();
        Jwt malformedMembership = Jwt.withTokenValue("malformed-membership")
                .header("alg", "RS256").subject("user-public-id")
                .issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim("token_use", "access").claim("mid", 42L).build();
        Jwt malformedPurpose = Jwt.withTokenValue("malformed-purpose")
                .header("alg", "RS256").subject("user-public-id")
                .issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim("token_use", 42L).claim("mid", "membership-public-id").build();

        assertThatThrownBy(() -> converter.convert(malformedMembership))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(malformedPurpose))
                .isInstanceOf(OAuth2AuthenticationException.class);
        org.mockito.Mockito.verifyNoInteractions(membershipAccessService);
    }

    private static Jwt accessToken(String subject, String membershipId) {
        return accessToken(subject, membershipId, "access");
    }

    private static Jwt accessToken(String subject, String membershipId, String tokenUse) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("token_use", tokenUse);
        if (subject != null) {
            builder.subject(subject);
        }
        if (membershipId != null) {
            builder.claim("mid", membershipId);
        }
        return builder.build();
    }
}
