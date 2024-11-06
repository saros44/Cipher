package com.example.Cipher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.example.Cipher.repository")
@EntityScan(basePackages = "com.example.Cipher.model")

public class CipherApplication {

	public static void main(String[] args) {
		SpringApplication.run(CipherApplication.class, args);
	}

}
