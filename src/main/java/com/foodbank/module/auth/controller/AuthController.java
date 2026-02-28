package com.foodbank.module.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.JwtUtils;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.IUserService;
import com.foodbank.module.auth.model.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
// 🚨 引入 Spring 自带的加密工具类
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "安全认证接口", description = "负责用户登录、登出及Token签发")
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private IUserService userService;

    @Operation(summary = "系统统一登录入口", description = "校验手机号与密码，并进行 RBAC 角色鉴权，返回包含角色信息的VO")
    @PostMapping("/login")
    public Result<LoginVO> login(
            @Parameter(description = "手机号", example = "13800000000") @RequestParam String phone,
            @Parameter(description = "密码", example = "123456") @RequestParam String password) {

        // 1. 根据手机号查询统一用户表
        User user = userService.getOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, phone)
        );

        // 2. 基础校验
        if (user == null) {
            throw new BusinessException("该手机号未注册");
        }

        // 🚨 核心修复 1：将前端传来的明文密码进行 MD5 加密后，再与数据库比对
        String md5Password = DigestUtils.md5DigestAsHex(password.getBytes());
        if (!user.getPassword().equals(md5Password)) {
            throw new BusinessException("密码错误，请重新输入");
        }

        if (user.getStatus() == 0) {
            throw new BusinessException("该账号已被系统封禁");
        }

        // 🚨 核心修复 2：放开限制，允许 1-受赠方, 3-志愿者, 4-管理员 登录
        if (user.getRole() != 1 && user.getRole() != 3 && user.getRole() != 4) {
            throw new BusinessException("权限不足：系统暂未对该角色开放登录");
        }

        // 4. 校验通过，签发 Token 并存入 Redis
        Long realUserId = user.getUserId();
        String token = jwtUtils.generateTokenAndCache(realUserId, user.getRole());

        log.info("角色 [{}] 用户 [{}-{}] 登录成功", user.getRole(), realUserId, user.getUsername());

        // 5. 组装包含 token 和 身份信息 的 LoginVO 对象返回给前端
        LoginVO loginVO = LoginVO.builder()
                .token(token)
                .userId(realUserId)
                .username(user.getUsername())
                .role(user.getRole())
                .build();

        return Result.success(loginVO, "登录成功，欢迎回来：" + user.getUsername());
    }

    @Operation(summary = "强制登出 / 下线", description = "直接删除 Redis 中的 Token 缓存")
    @PostMapping("/logout")
    public Result<String> logout(
            @Parameter(description = "用户ID") @RequestParam Long userId) {
        jwtUtils.invalidateToken(userId);
        return Result.success("账号已成功退出登录");
    }
}