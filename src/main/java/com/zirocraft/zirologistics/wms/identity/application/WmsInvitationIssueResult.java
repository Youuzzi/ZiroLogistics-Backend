package com.zirocraft.zirologistics.wms.identity.application;

import java.time.Instant;

/**
 * Non-sensitive result of queuing a tenant invitation.
 * The bearer token is deliberately never included in this result.
 */
public record WmsInvitationIssueResult(long tokenId, Instant expiresAt) {
    public WmsInvitationIssueResult {
        if (tokenId < 1) {
            throw new IllegalArgumentException("tokenId must be positive");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
    }
}
