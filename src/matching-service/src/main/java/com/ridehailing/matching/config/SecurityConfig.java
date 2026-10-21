package com.ridehailing.matching.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * T07-1 (F01/N10) — Spring Security chain cho matching-service.
 * matching-service không có REST API trong contract §3/§4 (consumer internal thuộc T15/T23).
 * Mọi path mở để path lạ tới MVC -> 404 JSON theo contract §1.2; StrictHttpFirewall vẫn chặn
 * ';', %2F, .. v.v. trong URI -> 400. Khi thêm endpoint internal (T15+) sẽ thêm rule auth tương ứng.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // API stateless — không cần CSRF; session không tạo.
        http.csrf(csrf -> csrf.disable());
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
