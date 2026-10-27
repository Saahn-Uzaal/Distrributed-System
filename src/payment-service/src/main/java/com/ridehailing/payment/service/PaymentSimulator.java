package com.ridehailing.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * T23 — simulate success/failure cấu hình dev (spec §20.5): mỗi lần thu tiền giả lập,
 * xác suất decline lấy từ {@code app.payment.simulate-fail-rate} (env PAYMENT_SIMULATE_FAIL_RATE).
 * 0 = luôn thành công; 1 = luôn decline. Deterministic ở hai đầu cho test.
 */
@Component
public class PaymentSimulator {

    private volatile double failRate;

    public PaymentSimulator(@Value("${app.payment.simulate-fail-rate:0}") double failRate) {
        this.failRate = clamp(failRate);
    }

    /** true → decline giả lập (PaymentFailed, reason SIMULATED_DECLINE). */
    public boolean shouldDecline() {
        return ThreadLocalRandom.current().nextDouble() < failRate;
    }

    /** Test seam: đặt tỷ lệ deterministic trong test; clamp [0,1]. */
    public void setFailRate(double rate) {
        this.failRate = clamp(rate);
    }

    private static double clamp(double r) {
        return Math.min(1.0, Math.max(0.0, r));
    }
}
