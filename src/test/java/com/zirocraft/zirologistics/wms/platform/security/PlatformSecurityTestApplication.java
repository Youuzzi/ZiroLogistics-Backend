package com.zirocraft.zirologistics.wms.platform.security;

import com.zirocraft.zirologistics.wms.security.config.WmsCorsConfiguration;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({WmsSecurityConfiguration.class, WmsCorsConfiguration.class})
class PlatformSecurityTestApplication {
}