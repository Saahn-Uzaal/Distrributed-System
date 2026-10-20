package com.ridehailing.trip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Trip service (port 8083): trips, fare, Saga orchestration, notifications.
 * Skeleton stage (T02) — no business endpoints yet.
 */
@SpringBootApplication
public class TripServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TripServiceApplication.class, args);
    }
}
