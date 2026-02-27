package com.foodbank.config;

import com.foodbank.module.auth.interceptor.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置类：注册拦截器与资源映射
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                // 1. 拦截所有业务请求
                .addPathPatterns("/**")
                // 2. 精确放行白名单
                .excludePathPatterns(
                        // --- 认证模块 ---
                        "/auth/login",             // 必须放行，否则没法领 Token
                        "/auth/register",          // 注册接口

                        // --- 静态资源与图片 ---
                        "/favicon.ico",            // 浏览器小图标
                        "/upload/**",              // 假设你的志愿者证明材料存在这里

                        // --- Swagger / Knife4j 接口文档 (非常关键) ---
                        "/doc.html",               // Knife4j 文档入口
                        "/webjars/**",             // 接口文档静态资源
                        "/v3/api-docs/**",         // OpenAPI 数据源
                        "/swagger-ui/**",          // Swagger 原生 UI

                        // --- 系统异常 ---
                        "/error"                   // Spring Boot 内部异常转发
                );
    }
}