package com.zirocraft.zirologistics.wms.identity.application;

/**
 * One-time operator-supplied bootstrap input.
 *
 * The password is intentionally redacted from the generated record toString.
 */
public record WmsPlatformBootstrapCommand(
        String tenantCode,
        String tenantLegalName,
        String tenantDisplayName,
        String adminEmail,
        String adminFullName,
        String adminPassword) {

    @Override
    public String toString() {
        return "WmsPlatformBootstrapCommand[tenantCode=" + tenantCode
                + ", tenantLegalName=" + tenantLegalName
                + ", tenantDisplayName=" + tenantDisplayName
                + ", adminEmail=" + adminEmail
                + ", adminFullName=" + adminFullName
                + ", adminPassword=[REDACTED]]";
    }
}
