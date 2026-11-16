package com.zirocraft.zirologistics.wms.identity.infrastructure.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-supplied one-time bootstrap inputs. Do not log this object because
 * it contains the initial administrator password.
 */
@Getter
@Setter
@NoArgsConstructor
@ConfigurationProperties(prefix = "wms.bootstrap")
public class WmsPlatformBootstrapProperties {

    private String tenantCode;
    private String tenantLegalName;
    private String tenantDisplayName;
    private String adminEmail;
    private String adminFullName;
    private String adminPassword;
}
