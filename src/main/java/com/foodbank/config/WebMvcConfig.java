package com.foodbank.config;

import com.foodbank.module.auth.interceptor.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置类：注册拦截器
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                // 1. 拦截所有请求
                .addPathPatterns("/**")
                // 2. 划定白名单放行策略
                .excludePathPatterns(
                        "/auth/login",        // 放行登录接口
                        "/auth/register",     // 放行注册接口
                        "/swagger-ui/**",     // 放行 Swagger UI 文档
                        "/v3/api-docs/**",    // 放行 OpenAPI 数据源
                        "/error"              // 放行 Spring Boot 默认错误转发
                );
    }
}