package com.ridehailing.matching;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Matching service (port 8084): matching jobs, ranking, proposal history.
 * Skeleton stage (T02) — no business endpoints yet.
 */
@SpringBootApplication
public class MatchingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchingServiceApplication.class, args);
    }
}
