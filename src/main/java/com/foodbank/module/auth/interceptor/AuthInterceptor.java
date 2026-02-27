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
 * 全局登录拦截器 - 负责身份核验与安全防线
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtils jwtUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 核心修复：放行跨域预检请求 (OPTIONS)
        // 浏览器在发起真正请求前会发一个 OPTIONS 请求，此时不会带 Token，必须直接放行
        if (HttpMethod.OPTIONS.toString().equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 2. 从请求头获取 Authorization
        String authHeader = request.getHeader("Authorization");

        // 3. 校验格式：必须以 "Bearer " 开头
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            log.warn("🚨 非法访问：URI [{}] 未携带合法的 Authorization 报头", request.getRequestURI());
            throw new BusinessException(ResultCode.UNAUTHORIZED); //
        }

        // 4. 提取并验证 Token
        String token = authHeader.substring(7);
        // 这里会同时校验 JWT 合法性及 Redis 是否存在缓存
        Long userId = jwtUtils.validateTokenAndCheckRedis(token);

        if (userId == null) {
            log.warn("⚠️ 鉴权失败：Token 已过期或无效，URI: {}", request.getRequestURI());
            throw new BusinessException(ResultCode.UNAUTHORIZED); //
        }

        // 5. 🚀 身份透传：挂载到当前线程上下文
        // 这样你在 Controller 里直接用 UserContext.getUserId() 就能拿到 ID，不用前端传参了！
        UserContext.setUserId(userId);
        log.info("✅ 鉴权通过：用户ID [{}] 正在访问 [{}]", userId, request.getRequestURI());

        return true;
    }

    /**
     * 🚨 极其重要：请求完成后清理 ThreadLocal
     * 必须手动清除，防止线程池场景下的内存泄漏或身份信息串线
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束后，务必清理 ThreadLocal
        UserContext.remove();
        log.debug("🧹 线程上下文已清理：URI [{}]", request.getRequestURI());
    }
}