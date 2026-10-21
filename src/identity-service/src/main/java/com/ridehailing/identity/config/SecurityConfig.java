package com.ridehailing.identity.config;

import com.ridehailing.identity.security.JwtAuthenticationFilter;
import com.ridehailing.identity.security.JsonAccessDeniedHandler;
import com.ridehailing.identity.security.JsonAuthEntryPoint;
import com.ridehailing.identity.service.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * T07-1 (F01/N10) — Spring Security chain cho identity-service.
 *
 * Thay thế JwtAuthFilter cũ (so khớp URI thô -> bỏ qua được bằng matrix parameter/encoding).
 * - StrictHttpFirewall mặc định chặn ';', %2F, .. v.v. trong URI -> 400 trước mọi rule.
 * - authorizeHttpRequests dùng PathPatternRequestMatcher trên path đã decode/normalize:
 *   /api/auth/me yêu cầu xác thực; register/login public; actuator mở.
 * - anyRequest().permitAll() (KHÔNG denyAll): path lạ vẫn tới MVC -> 404 JSON theo contract §1.2,
 *   không phải 403 — mọi endpoint bảo vệ được liệt kê rõ ở trên.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwt) {
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
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                .requestMatchers("/api/auth/me").authenticated()
                .anyRequest().permitAll());
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint(new JsonAuthEntryPoint(objectMapper))
                .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)));
        // Vị trí chuẩn của authentication filter: sau UsernamePasswordAuthenticationFilter.
        http.addFilterAfter(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
