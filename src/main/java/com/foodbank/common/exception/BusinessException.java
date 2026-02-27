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
     * 核心修正：默认使用 BAD_REQUEST (400)
     * 代表这是业务层面的逻辑拦截，而不是系统崩溃
     */
    public BusinessException(String message) {
        super(message);
        this.resultCode = ResultCode.BAD_REQUEST;
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }
}