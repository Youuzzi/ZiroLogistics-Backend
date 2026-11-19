package com.zirocraft.zirologistics.wms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = WmsRuntimeApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:wms_runtime_boundary;DB_CLOSE_DELAY=-1;MODE=MySQL",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=false"
        })
@ActiveProfiles("wms")
class WmsRuntimeBoundaryIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void wmsRuntimeDoesNotLoadLegacyApplicationComponents() {
        assertFalse(applicationContext.containsBean("authController"),
                "Legacy authentication controller must not be loaded by WMS runtime");
        assertFalse(applicationContext.containsBean("warehouseController"),
                "Legacy warehouse controller must not be loaded by WMS runtime");
        assertTrue(applicationContext.getType("userRepository").getName()
                        .startsWith("com.zirocraft.zirologistics.wms."),
                "The user repository bean must resolve to the WMS identity persistence package");
        assertFalse(applicationContext.containsBean("authServiceImpl"),
                "Legacy authentication service must not be loaded by WMS runtime");
        assertFalse(applicationContext.containsBean("jwtRequestFilter"),
                "Legacy JWT filter must not be loaded by WMS runtime");
        assertFalse(applicationContext.containsBean("auditorProvider"),
                "Legacy JPA auditing configuration must not be loaded by WMS runtime");
    }
}
