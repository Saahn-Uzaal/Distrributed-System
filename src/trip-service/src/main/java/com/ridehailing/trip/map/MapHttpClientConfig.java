package com.ridehailing.trip.map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class MapHttpClientConfig {
    private SimpleClientHttpRequestFactory factory(long timeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        int timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1, timeoutMs));
        factory.setConnectTimeout(Math.min(timeout, 1500));
        factory.setReadTimeout(timeout);
        return factory;
    }

    @Bean("nominatimRestClient")
    RestClient nominatimRestClient(
            @Value("${app.map.nominatim-base-url:https://nominatim.openstreetmap.org}") String baseUrl,
            @Value("${app.map.user-agent:DS03-RideHailing/0.1 (local-demo)}") String userAgent,
            @Value("${app.map.http-timeout-ms:4000}") long timeoutMs) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory(timeoutMs))
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept-Language", "vi,en;q=0.8")
                .build();
    }

    @Bean("osrmRestClient")
    RestClient osrmRestClient(
            @Value("${app.map.osrm-base-url:https://router.project-osrm.org}") String baseUrl,
            @Value("${app.map.user-agent:DS03-RideHailing/0.1 (local-demo)}") String userAgent,
            @Value("${app.map.osrm-timeout-ms:5000}") long timeoutMs) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory(timeoutMs))
                .defaultHeader("User-Agent", userAgent).build();
    }
}
