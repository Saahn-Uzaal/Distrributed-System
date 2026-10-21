package com.ridehailing.trip.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** T12-3 (N32 trip): bật scheduling cho cleanup idempotency keys cũ. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
