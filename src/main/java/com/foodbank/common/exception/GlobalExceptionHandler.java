package com.foodbank.common.exception;

import com.foodbank.common.api.Result;
import com.foodbank.common.api.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理 - 最终完美版
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 1. 处理自定义业务异常 (核心修复：显式返回 400 状态码)
     */
    @ExceptionHandler(value = BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("🚨 业务逻辑拦截: {}", e.getMessage());

        // 如果异常自带了具体的枚举码，提取它的 code，但强制使用我们自定义的 message
        if (e.getResultCode() != null) {
            return Result.failed(e.getResultCode().getCode(), e.getMessage());
        }

        // 兜底：直接返回 400 和自定义消息
        return Result.failed(400, e.getMessage());
    }

    /**
     * 2. 处理路径不存在异常 (解决之前那个 swagger-ui、 顿号 404 报错)
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<?> handleNotFoundException(NoResourceFoundException e) {
        log.warn("🧭 访问路径不存在: {}", e.getResourcePath());
        return Result.failed(404, "请求路径不存在，请检查接口地址是否正确", null);
    }

    /**
     * 3. 处理参数校验异常
     */
    @ExceptionHandler(value = {MethodArgumentNotValidException.class, BindException.class})
    public Result<?> handleValidException(Exception e) {
        String message = "参数校验失败";
        if (e instanceof MethodArgumentNotValidException ex) {
            FieldError fieldError = ex.getBindingResult().getFieldError();
            if (fieldError != null) message = fieldError.getDefaultMessage();
        } else if (e instanceof BindException ex) {
            FieldError fieldError = ex.getBindingResult().getFieldError();
            if (fieldError != null) message = fieldError.getDefaultMessage();
        }
        log.warn("📋 参数格式错误: {}", message);
        return Result.failed(ResultCode.VALIDATE_FAILED.getCode(), message, null);
    }

    /**
     * 4. 兜底处理：真正的系统崩溃
     */
    @ExceptionHandler(value = Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("🔥 系统崩溃异常堆栈: ", e);
        return Result.failed(ResultCode.FAILED);
    }
}