package com.example.Cipher;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.example.Cipher.repository")
@EntityScan(basePackages = "com.example.Cipher.model")
public class CipherApplication implements CommandLineRunner {

	@Autowired
	private Environment environment;

	public static void main(String[] args) {
		SpringApplication.run(CipherApplication.class, args);
	}

	@Override
	public void run(String... args) {
		try {
			String localhost = getLocalIp();
			String serverPort = environment.getProperty("server.port");
			boolean isSslEnabled = environment.getProperty("server.ssl.enabled", Boolean.class, false);
			String protocol = isSslEnabled ? "https" : "http";
			System.out.println("\t Local: " + protocol + "://localhost:" + serverPort);
			System.out.println("\t External: " + protocol + "://" + localhost + ":" + serverPort);
			System.out.println();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private String getLocalIp() {
		try {
			Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
			while (nics.hasMoreElements()) {
				NetworkInterface nic = nics.nextElement();
				Enumeration<InetAddress> addrs = nic.getInetAddresses();
				while (addrs.hasMoreElements()) {
					InetAddress addr = addrs.nextElement();
					if (!addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
						return addr.getHostAddress();
					}
				}
			}
		} catch (Exception ignored) {}
		return "127.0.0.1";
	}
}