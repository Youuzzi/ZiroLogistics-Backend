package com.zirocraft.zirologistics.wms.security.authentication.http;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Enables the WMS HTTP API only when JWT authentication is explicitly enabled.
 *
 * The public authentication endpoint toggle is intentionally not part of route
 * registration: it controls only which exact POST routes the security chain
 * permits anonymously. When disabled, all registered WMS routes remain
 * protected by JWT authentication.
 */
public final class WmsHttpApiEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return "true".equalsIgnoreCase(
                context.getEnvironment().getProperty("wms.security.jwt.enabled"));
    }
}
