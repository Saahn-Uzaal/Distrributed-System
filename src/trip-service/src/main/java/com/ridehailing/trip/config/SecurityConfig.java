package com.ridehailing.trip.config;

import com.ridehailing.trip.security.JwtAuthenticationFilter;
import com.ridehailing.trip.security.JsonAccessDeniedHandler;
import com.ridehailing.trip.security.JsonAuthEntryPoint;
import com.ridehailing.trip.security.JwtVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * T07-1 (F01/N10) — Spring Security chain cho trip-service.
 *
 * Thay thế ApiSecurityFilter cũ (so khớp URI thô -> bỏ qua được bằng matrix parameter/encoding).
 * - StrictHttpFirewall mặc định chặn ';', %2F, .. v.v. trong URI -> 400 trước mọi rule.
 * - contract §3.3: POST /api/trips + /api/trips/quote -> PASSENGER;
 *   POST /api/trips/{id}/accept|reject|start|complete -> DRIVER;
 *   các path còn lại của /api/trips/**, /api/notifications/** -> mọi role đã xác thực.
 * - anyRequest().permitAll() (KHÔNG denyAll): path lạ tới MVC -> 404 JSON theo contract §1.2.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtVerifier jwt) {
        return new JwtAuthenticationFilter(jwt);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthFilter,
                                                   tools.jackson.databind.ObjectMapper objectMapper) throws Exception {
        // API stateless (JWT bearer) — không cần CSRF; session không tạo.
        http.csrf(csrf -> csrf.disable());
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                // POST quote/create: chỉ passenger (contract §3.3)
                .requestMatchers(HttpMethod.POST, "/api/trips", "/api/trips/quote").hasRole("PASSENGER")
                .requestMatchers(HttpMethod.GET, "/api/locations/search").hasRole("PASSENGER")
                // T20: cancel chỉ passenger (owner kiểm tra ở service; người khác → 404)
                .requestMatchers(HttpMethod.POST, "/api/trips/*/cancel").hasRole("PASSENGER")
                // POST accept/reject/start/complete: chỉ driver (contract §3.3)
                .requestMatchers(HttpMethod.POST,
                        "/api/trips/*/accept", "/api/trips/*/reject",
                        "/api/trips/*/start", "/api/trips/*/complete").hasRole("DRIVER")
                // GET list/detail + notifications: mọi role đã xác thực
                .requestMatchers("/api/trips", "/api/trips/**", "/api/locations/**", "/api/notifications", "/api/notifications/**")
                    .authenticated()
                .anyRequest().permitAll());
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint(new JsonAuthEntryPoint(objectMapper))
                .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)));
        // Vị trí chuẩn của authentication filter: sau UsernamePasswordAuthenticationFilter.
        http.addFilterAfter(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** L04 — firewall rejection trả ApiError JSON (WebSecurity tự nhận bean này). */
    @Bean
    public org.springframework.security.web.firewall.RequestRejectedHandler requestRejectedHandler(
            tools.jackson.databind.ObjectMapper objectMapper) {
        return new com.ridehailing.trip.security.JsonRequestRejectedHandler(objectMapper);
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
}
