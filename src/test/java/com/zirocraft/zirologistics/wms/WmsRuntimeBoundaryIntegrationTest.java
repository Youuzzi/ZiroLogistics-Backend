package com.zirocraft.zirologistics.wms;

import com.zirocraft.zirologistics.controller.AuthController;
import com.zirocraft.zirologistics.controller.WarehouseController;
import com.zirocraft.zirologistics.entity.UserEntity;
import com.zirocraft.zirologistics.repository.UserRepository;
import com.zirocraft.zirologistics.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(
        classes = WmsRuntimeApplication.class,
        properties = {
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
        }
)
class WmsRuntimeBoundaryIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void wmsRuntimeDoesNotLoadLegacyApplicationComponents() {
        assertFalse(applicationContext.getBeansOfType(AuthController.class).size() > 0,
                "Legacy authentication controller must not be loaded by WMS runtime");
        assertFalse(applicationContext.getBeansOfType(WarehouseController.class).size() > 0,
                "Legacy warehouse controller must not be loaded by WMS runtime");
        assertFalse(applicationContext.getBeansOfType(UserEntity.class).size() > 0,
                "Legacy user entity must not be registered as a WMS bean");
        assertFalse(applicationContext.getBeansOfType(UserRepository.class).size() > 0,
                "Legacy user repository must not be loaded by WMS runtime");
        assertFalse(applicationContext.getBeansOfType(AuthServiceImpl.class).size() > 0,
                "Legacy authentication service must not be loaded by WMS runtime");
    }
}
