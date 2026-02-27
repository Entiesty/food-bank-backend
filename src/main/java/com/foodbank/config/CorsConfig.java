package com.foodbank.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        // 1. 允许所有的域（即允许 5173 访问 8080）
        config.addAllowedOriginPattern("*");

        // 2. 允许所有的请求头
        config.addAllowedHeader("*");

        // 3. 允许所有的请求方法 (GET, POST, PUT, DELETE, OPTIONS)
        config.addAllowedMethod("*");

        // 4. 允许携带 Cookie 或认证信息 (JWT 必备)
        config.setAllowCredentials(true);

        // 5. 对所有接口生效
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}