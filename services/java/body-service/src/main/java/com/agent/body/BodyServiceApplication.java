package com.agent.body;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
@SpringBootApplication
@EnableDiscoveryClient
public class BodyServiceApplication { public static void main(String[] args) { SpringApplication.run(BodyServiceApplication.class, args); } }
