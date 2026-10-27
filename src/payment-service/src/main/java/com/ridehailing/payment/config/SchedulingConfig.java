package com.ridehailing.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** T23 — bật scheduling cho outbox publish worker (pattern trip-service T13/matching T15). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
