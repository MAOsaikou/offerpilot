package com.offerpilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.web.AuthRateLimitFilter;
import com.offerpilot.ratelimit.AuthRateLimiter;
import com.offerpilot.mcp.McpApiKeyFilter;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final OfferPilotProperties properties;

    public SecurityConfig(OfferPilotProperties properties) {
        this.properties = properties;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthRateLimiter limiter, ObjectMapper json, Environment environment) throws Exception {
        var mcpAuth = new McpApiKeyFilter(properties.mcp().serverApiKey(), json, environment);
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .addFilterBefore(mcpAuth, CorsFilter.class)
                // Construct here only: no servlet auto-registration or duplicate counting.
                .addFilterAfter(new AuthRateLimitFilter(limiter, properties.rateLimit(), json), CorsFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/guest",
                                "/api/v1/auth/staff/login",
                                "/api/v1/auth/demo-login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout"
                        ).permitAll()
                        .requestMatchers(mcpAuth::isMcpRequest).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Spring Security leaves API routing open; CurrentUser/JWT staff gates protect resources.
                        // Public auth endpoints are listed explicitly above for clarity.
                        .requestMatchers("/api/v1/**").permitAll()
                        .anyRequest().authenticated()
                );
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = properties.security().corsOriginList();
        if (origins.isEmpty()) {
            config.setAllowedOriginPatterns(List.of("http://localhost:*", "https://localhost:*"));
        } else {
            config.setAllowedOrigins(origins);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Trace-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining", "Retry-After", "Content-Disposition"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
