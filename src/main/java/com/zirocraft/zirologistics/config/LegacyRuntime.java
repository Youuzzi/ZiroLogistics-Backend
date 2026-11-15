package com.zirocraft.zirologistics.config;

import org.springframework.context.annotation.Profile;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks components that belong exclusively to the legacy application runtime.
 * The WMS runtime has an independent bootstrap and does not scan this package.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Profile("!wms")
public @interface LegacyRuntime {
}
