package com.agentic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

import java.time.Clock;

/**
 * One deployable with two functionalities that never call each other:
 * {@code com.agentic.shortener} (the product) and {@code com.agentic.orchestrator}
 * (the governed agentic SDLC engine that changes the product's source code).
 */
@SpringBootApplication
@EnableAsync
@ConfigurationPropertiesScan
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
