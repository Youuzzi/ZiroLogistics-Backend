package com.zirocraft.zirologistics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing; // Tambahkan ini

@SpringBootApplication
@EnableJpaAuditing // SANGAT PENTING: Untuk mengaktifkan fitur Audit Trail
public class ZirologisticsApplication {

	public static void main(String[] args) {
		SpringApplication.run(ZirologisticsApplication.class, args);
	}

}