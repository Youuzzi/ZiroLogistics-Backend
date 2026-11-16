package com.zirocraft.zirologistics.wms.identity.application;

/**
 * Non-sensitive result of a successful invitation acceptance.
 */
public record WmsInvitationAcceptanceResult(
        long tenantId,
        String normalizedEmail,
        boolean accountCreated) {

    public WmsInvitationAcceptanceResult {
        if (tenantId < 1) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            throw new IllegalArgumentException("normalizedEmail must not be blank");
        }
    }
}
