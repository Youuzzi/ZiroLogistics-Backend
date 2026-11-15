package com.zirocraft.zirologistics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EntityScan(basePackages = "com.zirocraft.zirologistics.entity")
@EnableJpaRepositories(basePackages = "com.zirocraft.zirologistics.repository")
@EnableJpaAuditing
public class ZirologisticsApplication {

	public static void main(String[] args) {
		SpringApplication.run(ZirologisticsApplication.class, args);
	}

}