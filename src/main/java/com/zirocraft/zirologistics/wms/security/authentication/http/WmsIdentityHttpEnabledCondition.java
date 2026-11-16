package com.zirocraft.zirologistics.wms.security.authentication.http;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Enables WMS identity HTTP adapters only when both security switches are explicit. */
public final class WmsIdentityHttpEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return "true".equalsIgnoreCase(
                    context.getEnvironment().getProperty("wms.security.jwt.enabled"))
                && "true".equalsIgnoreCase(
                    context.getEnvironment().getProperty("wms.security.http.auth-endpoints-enabled"));
    }
}
