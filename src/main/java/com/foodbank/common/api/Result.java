package com.foodbank.common.api;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 全局统一响应体 - 增强版
 */
@Data
public class Result<T> {

    private long code;
    private String message;
    private T data;
    private LocalDateTime timestamp;

    protected Result() {
        this.timestamp = LocalDateTime.now();
    }

    protected Result(long code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = LocalDateTime.now();
    }

    /**
     * 成功返回结果
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> success(T data, String message) {
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    /**
     * 失败返回结果 (只传错误码枚举)
     */
    public static <T> Result<T> failed(ResultCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 失败返回结果 (自定义错误信息，手动指定错误码)
     * 🚨 推荐：业务拦截使用 400，系统崩溃使用 500
     */
    public static <T> Result<T> failed(long code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 失败返回结果 (传自定义错误信息，默认 500 状态码)
     * ⚠️ 注意：仅用于真正的系统兜底异常
     */
    public static <T> Result<T> failed(String message) {
        return new Result<>(ResultCode.FAILED.getCode(), message, null);
    }

    /**
     * 失败返回结果 (全参数构造)
     */
    public static <T> Result<T> failed(long code, String message, T data) {
        return new Result<>(code, message, data);
    }
}