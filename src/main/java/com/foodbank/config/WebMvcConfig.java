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
                        // --- 🚨 认证模块 (全面放行) ---
                        "/auth/login",
                        "/auth/register",
                        "/auth/send-code",
                        "/auth/reset-password",

                        // --- 🚨 文件上传模块 (放行注册时的图片上传) ---
                        "/common/file/upload",  // 新增这一行！
                        "/common/file/**",      // 稳妥起见，把 common/file 下的都放行

                        // --- 数据大屏与通用 ---
                        "/dispatch/dashboard/**",
                        "/resource/station/list",
                        "/favicon.ico",
                        "/upload/**", // 这个可能是你以前本地存储留下的，保留即可

                        // --- 接口文档 ---
                        "/doc.html",
                        "/webjars/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/error"
                );
    }
}