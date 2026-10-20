package com.ridehailing.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Identity service (port 8081): users, credentials, login, JWT.
 * Skeleton stage (T02) — no business endpoints yet.
 */
@SpringBootApplication
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
