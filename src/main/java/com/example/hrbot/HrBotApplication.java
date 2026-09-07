package com.example.hrbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the HR Policy RAG assistant.
 *
 * <p>Component scanning starts from this package, so all application classes live in
 * {@code com.example.hrbot} or below.
 */
@SpringBootApplication
public class HrBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(HrBotApplication.class, args);
    }
}