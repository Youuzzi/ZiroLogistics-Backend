package com.zirocraft.zirologistics.wms;

import com.zirocraft.zirologistics.controller.AuthController;
import com.zirocraft.zirologistics.controller.WarehouseController;
import com.zirocraft.zirologistics.repository.UserRepository;
import com.zirocraft.zirologistics.service.impl.AuthServiceImpl;
import com.zirocraft.zirologistics.filter.JwtRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
@ActiveProfiles({"wms", "test"})
class WmsRuntimeBoundaryIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void wmsRuntimeDoesNotLoadLegacyApplicationComponents() {
        assertFalse(applicationContext.getBeansOfType(AuthController.class).size() > 0,
                "Legacy authentication controller must not be loaded by WMS runtime");
        assertFalse(applicationContext.getBeansOfType(WarehouseController.class).size() > 0,
                "Legacy warehouse controller must not be loaded by WMS runtime");
        assertFalse(applicationContext.getBeansOfType(UserRepository.class).size() > 0,
                "Legacy user repository must not be loaded by WMS runtime");
        assertFalse(applicationContext.getBeansOfType(AuthServiceImpl.class).size() > 0,
                "Legacy authentication service must not be loaded by WMS runtime");
        assertFalse(applicationContext.getBeansOfType(JwtRequestFilter.class).size() > 0,
                "Legacy JWT filter must not be loaded by WMS runtime");
        assertFalse(applicationContext.containsBean("auditorProvider"),
                "Legacy JPA auditing configuration must not be loaded by WMS runtime");
    }
}
