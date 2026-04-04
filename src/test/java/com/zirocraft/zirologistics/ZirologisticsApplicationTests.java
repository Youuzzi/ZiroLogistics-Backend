package com.zirocraft.zirologistics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test") // Ini penting supaya dia pake application-test.properties
class ZirologisticsApplicationTests {

	@Test
	void contextLoads() {
		// Test ini akan lulus jika aplikasi bisa startup dengan konfigurasi H2
	}

}