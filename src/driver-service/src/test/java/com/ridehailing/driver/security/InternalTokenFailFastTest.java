package com.ridehailing.driver.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** N08 (T07-12) — InternalTokenFilter fail-fast khi INTERNAL_API_TOKEN rỗng/short. */
class InternalTokenFailFastTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void emptyTokenRefusesToStart() {
        assertThatThrownBy(() -> new InternalTokenFilter("", json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_API_TOKEN");
    }

    @Test
    void nullTokenRefusesToStart() {
        assertThatThrownBy(() -> new InternalTokenFilter(null, json))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shortTokenRefusesToStart() {
        assertThatThrownBy(() -> new InternalTokenFilter("a".repeat(15), json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 16");
    }

    @Test
    void validLengthTokenAccepted() {
        // 23 ký tự — cùng độ dài với token test integration.
        assertThat(new InternalTokenFilter("test-internal-token-t09", json)).isNotNull();
    }
}
