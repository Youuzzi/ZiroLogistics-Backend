package com.zirocraft.zirologistics.wms.platform.support.application;

/** Explicit read-only support scopes; warehouse transaction data is intentionally absent. */
public enum PlatformSupportScope {
    TENANT_METADATA_READ,
    TENANT_CONFIGURATION_READ,
    INTEGRATION_DIAGNOSTICS_READ,
    TENANT_AUDIT_METADATA_READ
}
