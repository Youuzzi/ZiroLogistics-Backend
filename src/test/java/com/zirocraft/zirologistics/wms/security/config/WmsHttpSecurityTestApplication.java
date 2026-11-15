package com.zirocraft.zirologistics.wms.security.config;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal Boot configuration for WMS HTTP security slice tests.
 *
 * Deliberately excludes WmsRuntimeApplication because that production entry
 * point enables JPA entity and repository scanning, which is outside a
 * WebMvcTest slice and requires a database.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
class WmsHttpSecurityTestApplication {
}
