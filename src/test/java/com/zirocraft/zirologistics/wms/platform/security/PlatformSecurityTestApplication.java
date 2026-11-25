package com.zirocraft.zirologistics.wms.platform.security;

import com.zirocraft.zirologistics.wms.security.config.WmsCorsConfiguration;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.zirocraft.zirologistics.wms.platform.security")
@Import({WmsSecurityConfiguration.class, WmsCorsConfiguration.class})
class PlatformSecurityTestApplication {
}
