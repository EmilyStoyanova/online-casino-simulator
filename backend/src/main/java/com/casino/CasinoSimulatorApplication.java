package com.casino;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Online Casino Simulator modular monolith.
 *
 * <p>Each direct sub-package of {@code com.casino} is a business module with its own
 * responsibility; see the {@code package-info.java} of each module and
 * {@code docs/architecture.md} for the boundaries between them.
 */
@SpringBootApplication
public class CasinoSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CasinoSimulatorApplication.class, args);
    }
}
