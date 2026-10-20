package com.ridehailing.driver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Driver service (port 8082): drivers, vehicles, location, availability, reservations.
 * Profiles, availability, candidates and fenced reservation lifecycle (T09–T11).
 */
@SpringBootApplication
public class DriverServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriverServiceApplication.class, args);
    }
}
