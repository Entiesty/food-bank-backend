package com.foodbank.common.utils;

/**
 * 线程级用户上下文工具 (ThreadLocal)
 * 作用：在拦截器验证通过后，将当前用户的 ID 存入当前请求的线程中，方便业务层随时获取，防止数据越权。
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_THREAD_LOCAL = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        USER_THREAD_LOCAL.set(userId);
    }

    public static Long getUserId() {
        return USER_THREAD_LOCAL.get();
    }

    /**
     * 🚨 极其重要：防止内存泄漏，必须在请求结束后清除
     */
    public static void remove() {
        USER_THREAD_LOCAL.remove();
    }
}