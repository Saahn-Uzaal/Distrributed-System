package com.ridehailing.trip.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * T16 — HTTP client cho internal API driver-service (contract api-contracts.md §4.2/§7-8):
 * header {@code X-Internal-Token} (token dev dùng chung từ env), timeout hữu hạn để lỗi mạng/timeout
 * đi nhánh retry bounded của inbox consumer (events.md §4.3). Token không có giá trị mặc định —
 * RequiredDbPasswordValidator dừng startup nếu thiếu (quy tắc N08/N09).
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient driverServiceRestClient(
            @Value("${app.trip.driver-service-url}") String baseUrl,
            @Value("${app.trip.internal-api-token}") String internalToken,
            @Value("${app.trip.http-timeout-ms:3000}") long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = (int) Math.min(Math.max(timeoutMs, 1), Integer.MAX_VALUE);
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }
}
