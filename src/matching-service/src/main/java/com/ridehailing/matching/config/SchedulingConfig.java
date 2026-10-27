package com.ridehailing.matching.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** T15 — bật scheduling cho outbox publish worker (pattern trip-service T13). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
