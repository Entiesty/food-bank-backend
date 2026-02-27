package com.foodbank.module.auth.controller;

import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.JwtUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "安全认证接口", description = "负责用户登录、登出及Token签发")
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private JwtUtils jwtUtils;

    @Operation(summary = "模拟志愿者登录", description = "校验账号密码，成功则返回双重校验的 JWT Token")
    @PostMapping("/login")
    public Result<String> login(
            @Parameter(description = "手机号", example = "13800000000") @RequestParam String phone,
            @Parameter(description = "密码", example = "123456") @RequestParam String password) {

        // 1. 模拟数据库查询校验 (真实项目中这里会去调用 IUserService)
        if (!"13800000000".equals(phone) || !"123456".equals(password)) {
            throw new BusinessException("手机号或密码错误");
        }

        // 2. 模拟校验通过，查出该名志愿者的真实 ID (比如是 888)
        Long volunteerId = 888L;

        // 3. 核心：调用 JwtUtils 生成 Token 并自动存入 Redis！
        String token = jwtUtils.generateTokenAndCache(volunteerId);

        log.info("志愿者 [{}] 登录成功，下发 Token", volunteerId);

        // 返回给前端
        return Result.success(token, "登录成功，欢迎回来！");
    }

    @Operation(summary = "强制登出 / 下线", description = "直接删除 Redis 中的 Token 缓存，实现秒级强制下线")
    @PostMapping("/logout")
    public Result<String> logout(
            @Parameter(description = "志愿者ID", example = "888") @RequestParam Long userId) {
        // 注：真实环境中，userId 会通过 UserContext.getUserId() 自动获取，这里为了方便测试暴露为参数
        jwtUtils.invalidateToken(userId);
        return Result.success("账号已成功退出登录");
    }
}