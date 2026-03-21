package com.foodbank.config;

import com.foodbank.module.auth.interceptor.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // --- 认证模块 ---
                        "/auth/**",
                        "/ws/**",
                        "/api/ws/**",

                        // --- 文件模块 ---
                        "/common/file/**",
                        "/upload/**",

                        // --- 数据大屏与通用 ---
                        "/dispatch/dashboard/**",
                        "/resource/station/list",
                        "/favicon.ico",
                        "/error",

                        // --- 重点：放行 Swagger / Knife4j 静态资源 ---
                        "/doc.html",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/v2/api-docs/**",
                        "/swagger-resources/**",
                        "/webjars/**",
                        "**/swagger-ui.html", // 防止路径前缀干扰
                        "/api/swagger-ui.html" // 如果你确实需要这个硬编码路径
                );
    }
}