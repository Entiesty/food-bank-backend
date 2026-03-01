package com.foodbank.module.system.user.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.model.dto.PasswordUpdateDTO;
import com.foodbank.module.system.user.model.dto.UserUpdateDTO;
import com.foodbank.module.system.user.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.DigestUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User Center", description = "用户个人中心与安全设置")
@RestController
@RequestMapping("/system/user")
public class UserController {

    @Autowired
    private IUserService userService;

    @Operation(summary = "1. 获取当前登录用户信息", description = "自动从上下文提取ID，过滤敏感字段")
    @GetMapping("/profile")
    public Result<User> getMyProfile() {
        Long userId = UserContext.getUserId();
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException("用户信息不存在");
        }
        user.setPassword(null); // 绝对不能把密码散列值下发给前端
        return Result.success(user);
    }

    @Operation(summary = "2. 更新个人基本信息", description = "目前支持修改姓名/昵称")
    @PutMapping("/profile")
    public Result<Void> updateProfile(@Validated @RequestBody UserUpdateDTO dto) {
        Long userId = UserContext.getUserId();
        boolean success = userService.update(new LambdaUpdateWrapper<User>()
                .eq(User::getUserId, userId)
                .set(User::getUsername, dto.getUsername()));

        if (!success) {
            throw new BusinessException("资料更新失败，请重试");
        }
        return Result.success(null, "资料更新成功！");
    }

    @Operation(summary = "3. 修改密码", description = "需要校验原密码")
    @PutMapping("/password")
    public Result<Void> updatePassword(@Validated @RequestBody PasswordUpdateDTO dto) {
        Long userId = UserContext.getUserId();
        User user = userService.getById(userId);

        // 校验旧密码
        String oldMd5 = DigestUtils.md5DigestAsHex(dto.getOldPassword().getBytes());
        if (!user.getPassword().equals(oldMd5)) {
            throw new BusinessException("旧密码不正确，请重新输入");
        }

        // 更新新密码
        String newMd5 = DigestUtils.md5DigestAsHex(dto.getNewPassword().getBytes());
        userService.update(new LambdaUpdateWrapper<User>()
                .eq(User::getUserId, userId)
                .set(User::getPassword, newMd5));

        return Result.success(null, "密码修改成功，下次请使用新密码登录");
    }
}