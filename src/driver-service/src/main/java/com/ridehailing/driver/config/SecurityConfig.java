package com.ridehailing.driver.config;

import com.ridehailing.driver.security.InternalTokenFilter;
import com.ridehailing.driver.security.JwtAuthenticationFilter;
import com.ridehailing.driver.security.JsonAccessDeniedHandler;
import com.ridehailing.driver.security.JsonAuthEntryPoint;
import com.ridehailing.driver.security.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * T07-1 (F01/N10) — Spring Security chain cho driver-service.
 *
 * Thay thế ApiSecurityFilter cũ (so khớp URI thô -> bỏ qua được bằng matrix parameter/encoding).
 * - StrictHttpFirewall mặc định chặn ';', %2F, .. v.v. trong URI -> 400 trước mọi rule.
 * - /internal/**: InternalTokenFilter xác minh X-Internal-Token (constant-time) và đặt
 *   principal ROLE_INTERNAL; rule hasRole("INTERNAL") enforce phía sau.
 * - /api/drivers + subpath: role DRIVER (contract §3.2).
 * - anyRequest().permitAll() (KHÔNG denyAll): path lạ tới MVC -> 404 JSON theo contract §1.2.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtVerifier jwt) {
        return new JwtAuthenticationFilter(jwt);
    }

    @Bean
    public InternalTokenFilter internalTokenFilter(
            @Value("${internal.api-token:}") String internalToken,
            tools.jackson.databind.ObjectMapper objectMapper) {
        return new InternalTokenFilter(internalToken, objectMapper);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthFilter,
                                                   InternalTokenFilter internalTokenFilter,
                                                   tools.jackson.databind.ObjectMapper objectMapper) throws Exception {
        // API stateless (JWT bearer + internal token) — không cần CSRF; session không tạo.
        http.csrf(csrf -> csrf.disable());
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/internal/**").hasRole("INTERNAL")
                .requestMatchers("/api/drivers", "/api/drivers/**").hasRole("DRIVER")
                .anyRequest().permitAll());
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint(new JsonAuthEntryPoint(objectMapper))
                .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)));
        // Internal token filter chạy trước JWT auth; cả hai đều trước AuthorizationFilter.
        http.addFilterAfter(internalTokenFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(jwtAuthFilter, InternalTokenFilter.class);
        return http.build();
    }

    /** L04 — firewall rejection trả ApiError JSON (WebSecurity tự nhận bean này). */
    @Bean
    public org.springframework.security.web.firewall.RequestRejectedHandler requestRejectedHandler(
            tools.jackson.databind.ObjectMapper objectMapper) {
        return new com.ridehailing.driver.security.JsonRequestRejectedHandler(objectMapper);
    }

    /** L10 — filter này chỉ chạy trong SecurityFilterChain; tắt đăng ký servlet tự động của Boot
     *  (mọi bean Filter đều bị Boot đăng ký thêm một lần ở servlet container). */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(JwtAuthenticationFilter filter) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** L10 — filter này chỉ chạy trong SecurityFilterChain; tắt đăng ký servlet tự động của Boot
     *  (mọi bean Filter đều bị Boot đăng ký thêm một lần ở servlet container). */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<InternalTokenFilter> internalTokenFilterRegistration(InternalTokenFilter filter) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<InternalTokenFilter> registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
