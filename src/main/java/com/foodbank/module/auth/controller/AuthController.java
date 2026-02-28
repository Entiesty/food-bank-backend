package com.foodbank.module.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.JwtUtils;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.IUserService;
import com.foodbank.module.auth.model.vo.LoginVO; // 🚨 引入新建的视图对象

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

    @Autowired
    private IUserService userService;

    @Operation(summary = "系统统一登录入口", description = "校验手机号与密码，并进行 RBAC 角色鉴权，返回包含角色信息的VO")
    @PostMapping("/login")
    // 🚨 注意这里：返回值泛型已经从 String 改成了 LoginVO
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
        if (!user.getPassword().equals(password)) {
            throw new BusinessException("密码错误，请重新输入");
        }
        if (user.getStatus() == 0) {
            throw new BusinessException("该账号已被系统封禁");
        }

        // 3. RBAC 权限校验：限制仅志愿者或管理员可登录此调度端
        // role: 1-受赠方, 2-供应商家, 3-志愿者, 4-管理员
        if (user.getRole() != 3 && user.getRole() != 4) {
            throw new BusinessException("权限不足：该入口仅限志愿者或管理员登录");
        }

        // 4. 校验通过，签发 Token 并存入 Redis
        Long realUserId = user.getUserId();
        String token = jwtUtils.generateTokenAndCache(realUserId, user.getRole());

        log.info("角色 [{}] 用户 [{}-{}] 登录成功", user.getRole(), realUserId, user.getUsername());

        // 🚨 5. 核心修改：组装包含 token 和 身份信息 的 LoginVO 对象返回给前端
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