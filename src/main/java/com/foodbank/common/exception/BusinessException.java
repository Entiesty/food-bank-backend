package com.foodbank.common.exception;

import com.foodbank.common.api.ResultCode;
import lombok.Getter;

/**
 * 自定义全局业务异常
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    public BusinessException(String message) {
        super(message);
        this.resultCode = ResultCode.FAILED; // 默认给个失败状态
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }
}