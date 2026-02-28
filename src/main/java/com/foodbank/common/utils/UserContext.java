package com.foodbank.common.utils;

/**
 * 线程级用户上下文工具 (ThreadLocal)
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID_LOCAL = new ThreadLocal<>();
    // 🚨 新增：用于存储当前用户的角色
    private static final ThreadLocal<Byte> USER_ROLE_LOCAL = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        USER_ID_LOCAL.set(userId);
    }

    public static Long getUserId() {
        return USER_ID_LOCAL.get();
    }

    public static void setUserRole(Byte role) {
        USER_ROLE_LOCAL.set(role);
    }

    public static Byte getUserRole() {
        return USER_ROLE_LOCAL.get();
    }

    /**
     * 🚨 极其重要：防止内存泄漏，必须在请求结束后清除所有 ThreadLocal
     */
    public static void remove() {
        USER_ID_LOCAL.remove();
        USER_ROLE_LOCAL.remove(); // 清理角色
    }
}