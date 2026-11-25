package com.zirocraft.zirologistics.wms.platform.support.application;

import java.time.Instant;
import java.util.Set;

/** Request data excludes requester identity; it is resolved from platform authentication. */
public record PlatformSupportGrantRequest(
        String tenantPublicId,
        String purpose,
        String operationRef,
        Instant startsAt,
        Instant expiresAt,
        Set<PlatformSupportScope> scopes) {
}
