package com.foodbank.module.system.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin Controller", description = "平台管理员核心控制台")
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private IUserService userService;

    @Operation(summary = "1. 获取待审核商家列表")
    @GetMapping("/merchant/pending")
    public Result<List<User>> getPendingMerchants() {
        // 🚨 权限防线：仅限管理员
        if (UserContext.getUserRole() != 4) {
            throw new BusinessException("越权访问：仅限平台管理员操作");
        }

        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        query.eq(User::getRole, 2).eq(User::getStatus, 0); // 角色2(商家)，状态0(待审核)
        return Result.success(userService.list(query));
    }

    @Operation(summary = "2. 审核商家资质", description = "pass传1表示通过，传-1表示驳回")
    @PostMapping("/merchant/audit")
    public Result<String> auditMerchant(@RequestParam Long userId, @RequestParam Integer pass) {
        if (UserContext.getUserRole() != 4) {
            throw new BusinessException("越权访问：仅限平台管理员操作");
        }

        User user = userService.getById(userId);
        if (user == null || user.getRole() != 2) {
            throw new BusinessException("非法操作：目标商家不存在");
        }

        // 状态流转：1 为正常使用，-1 为封禁/驳回
        user.setStatus(pass == 1 ? (byte) 1 : (byte) -1);
        userService.updateById(user);

        return Result.success(pass == 1 ? "✅ 已通过该商家的入驻申请" : "❌ 已驳回该商家的入驻申请");
    }
}