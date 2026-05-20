package com.example.sandbox.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Sandbox Agent 应用启动类
 *
 * @author example
 * @date 2026/05/14
 */
@SpringBootApplication
@EnableAsync
public class SandboxAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SandboxAgentApplication.class, args);
    }
}
