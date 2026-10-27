package com.ridehailing.payment;

import com.ridehailing.payment.event.InboxMessageValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class InboxMessageValidatorTest {
    private final InboxMessageValidator validator = new InboxMessageValidator(new ObjectMapper());

    private String event() {
        return """
                {"eventId":"00000000-0000-0000-0000-000000000001","eventType":"TripCompleted",
                 "schemaVersion":1,"occurredAt":"2026-10-27T00:00:00Z","producer":"test",
                 "correlationId":"00000000-0000-0000-0000-000000000002",
                 "aggregateId":"00000000-0000-0000-0000-000000000003","aggregateVersion":0,
                 "payload":{"tripId":"00000000-0000-0000-0000-000000000003",
                 "paymentId":"00000000-0000-0000-0000-000000000004",
                 "attempt":1,"amountVnd":10000,"currency":"VND"}}
                """;
    }

    private InboxMessageValidator.ValidationResult validate(String body) {
        return validator.validate(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void nullJsonIsMalformedInsteadOfThrowing() {
        assertThat(validate("null").reason()).isEqualTo("malformed_envelope");
    }

    @ParameterizedTest
    @CsvSource({
            "attempt,1,4294967297",
            "attempt,1,1.00000000000000001",
            "aggregateVersion,0,4294967296",
            "aggregateVersion,0,0.0000000000000000001",
            "amountVnd,10000,9223372036854775808",
            "amountVnd,10000,9223372036854775808.0",
            "amountVnd,10000,10000.000000000000001"
    })
    void invalidNumbersCannotWrapOrRoundIntoValidValues(String field, String original, String invalid) {
        assertThat(validate(event().replace("\"" + field + "\":" + original,
                "\"" + field + "\":" + invalid)).reason()).isEqualTo("malformed_envelope");
    }

    @Test
    void overflowingSchemaVersionIsUnsupported() {
        assertThat(validate(event().replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297")).reason())
                .isEqualTo("unsupported_schema_version");
    }

    @Test
    void maximumLongAmountIsPreservedExactly() {
        var result = validate(event().replace("\"amountVnd\":10000", "\"amountVnd\":9223372036854775807"));
        assertThat(result.ok()).isTrue();
        assertThat(((Number) result.envelope().payload().get("amountVnd")).longValue()).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @CsvSource({
            "attempt,1,1e999999999,malformed_envelope",
            "aggregateVersion,0,1e999999999,malformed_envelope",
            "amountVnd,10000,1e999999999,malformed_envelope",
            "amountVnd,10000,1e-999999999,malformed_envelope",
            "schemaVersion,1,1e999999999,unsupported_schema_version"
    })
    void hugeExponentsAreRejectedWithoutExpandingTheNumber(String field, String original, String invalid,
                                                            String reason) {
        String body = event().replace("\"" + field + "\":" + original, "\"" + field + "\":" + invalid);
        assertThat(assertTimeoutPreemptively(Duration.ofSeconds(2), () -> validate(body)).reason())
                .isEqualTo(reason);
    }
}
