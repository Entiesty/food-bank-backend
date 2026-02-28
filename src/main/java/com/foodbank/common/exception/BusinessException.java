package com.foodbank.common.exception;

import com.foodbank.common.api.ResultCode;
import lombok.Getter;

/**
 * 自定义全局业务异常 - 修正版
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    /**
     * 默认使用 BAD_REQUEST (400)
     */
    public BusinessException(String message) {
        super(message);
        this.resultCode = ResultCode.BAD_REQUEST;
    }

    /**
     * 只传入枚举状态码
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    // 👇 🚨 新增：同时支持指定枚举状态码和自定义报错信息
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }
}