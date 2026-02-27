package com.foodbank.common.api;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * API 统一响应状态码 - 企业级规范版
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    // 500 代表代码报了空指针、数据库断开等“意外崩溃”
    FAILED(500, "系统内部异常"),
    // 400 代表逻辑拦截，比如“库存不足”、“订单已被抢”
    BAD_REQUEST(400, "业务请求失败"),
    VALIDATE_FAILED(405, "参数检验失败"),
    UNAUTHORIZED(401, "暂未登录或token已经过期"),
    FORBIDDEN(403, "没有相关权限");

    private final long code;
    private final String message;
}