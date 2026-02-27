package com.foodbank.module.auth.interceptor;

import com.foodbank.common.api.ResultCode;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.JwtUtils;
import com.foodbank.common.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 全局登录拦截器
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtils jwtUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 放行前端跨域发起的 OPTIONS 预检请求
        if (HttpMethod.OPTIONS.toString().equals(request.getMethod())) {
            return true;
        }

        // 2. 从请求头获取 Token (标准格式：Authorization: Bearer <token>)
        String authHeader = request.getHeader("Authorization");
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            log.warn("拦截到未携带合法 Token 的请求: {}", request.getRequestURI());
            throw new BusinessException(ResultCode.UNAUTHORIZED); // 抛出 401
        }

        // 3. 截取真实 Token 并进行 Redis 双重校验
        String token = authHeader.substring(7);
        Long userId = jwtUtils.validateTokenAndCheckRedis(token);

        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED); // Token 错误、过期或被顶号，抛出 401
        }

        // 4. 🚀 身份验证通过！将 userId 挂载到当前线程上下文中
        UserContext.setUserId(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束后，务必清理 ThreadLocal 防止内存泄漏
        UserContext.remove();
    }
}