package com.foodbank.module.system.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin Controller", description = "平台管理员核心控制台")
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private IUserService userService;

    /**
     * 内部权限校验统一方法
     */
    private void checkAdminPermission() {
        if (UserContext.getUserRole() != 4) {
            throw new BusinessException("越权访问：仅限平台管理员操作");
        }
    }

    // ================== 1. 商家准入审核模块 ==================

    @Operation(summary = "1. 获取待审核商家列表")
    @GetMapping("/merchant/pending")
    public Result<List<User>> getPendingMerchants() {
        checkAdminPermission(); // 🚨 权限防线

        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        query.eq(User::getRole, 2).eq(User::getStatus, 0); // 角色2(商家)，状态0(待审核)
        List<User> list = userService.list(query);

        // 🚨 核心修正：数据脱敏，严防密码 Hash 泄露给前端
        list.forEach(u -> u.setPassword(null));
        return Result.success(list);
    }

    @Operation(summary = "2. 审核商家资质", description = "pass传1表示通过，传-1表示驳回")
    @PostMapping("/merchant/audit")
    public Result<String> auditMerchant(@RequestParam Long userId, @RequestParam Integer pass) {
        checkAdminPermission();

        User user = userService.getById(userId);
        if (user == null || user.getRole() != 2) {
            throw new BusinessException("非法操作：目标商家不存在");
        }

        // 🚨 核心修正：同时更新 status(登录权) 和 is_verified(资质核验标识)
        byte newStatus = pass == 1 ? (byte) 1 : (byte) -1;
        byte newVerified = pass == 1 ? (byte) 1 : (byte) 0;

        userService.update(new LambdaUpdateWrapper<User>()
                .eq(User::getUserId, userId)
                .set(User::getStatus, newStatus)
                .set(User::getIsVerified, newVerified));

        return Result.success(pass == 1 ? "✅ 已通过该商家的入驻申请" : "❌ 已驳回该商家的入驻申请");
    }

    // ================== 2. 全域用户治理模块 (三维治理核心) ==================

    @Operation(summary = "3. 获取全域用户列表", description = "按角色和关键词检索用户，支持分页")
    @GetMapping("/user/list")
    public Result<Page<User>> getUserList(
            @RequestParam Byte role,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {

        checkAdminPermission();

        Page<User> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        query.eq(User::getRole, role);

        if (StringUtils.hasText(keyword)) {
            query.and(q -> q.like(User::getUsername, keyword).or().like(User::getPhone, keyword));
        }
        query.orderByDesc(User::getUserId);

        Page<User> result = userService.page(page, query);

        // 🚨 数据脱敏
        result.getRecords().forEach(u -> u.setPassword(null));
        return Result.success(result);
    }

    @Operation(summary = "4. 受赠方：更新弱势群体身份标签", description = "管理员核实材料后手动打标")
    @PutMapping("/user/update-tag")
    public Result<Void> updateUserTag(@RequestParam Long userId, @RequestParam String tag, @RequestParam Byte isVerified) {
        checkAdminPermission();

        boolean success = userService.update(new LambdaUpdateWrapper<User>()
                .eq(User::getUserId, userId)
                .set(User::getUserTag, tag)
                .set(User::getIsVerified, isVerified));

        if (!success) throw new BusinessException("标签更新失败");
        return Result.success(null, "身份标签与核验状态已更新，该受赠方已获得算法调度特权！");
    }

    @Operation(summary = "5. 志愿者：信誉分人工干预", description = "处理投诉或表彰时的后台加减分")
    @PutMapping("/user/update-credit")
    public Result<Void> updateUserCredit(@RequestParam Long userId, @RequestParam Integer scoreChange) {
        checkAdminPermission();

        // 🚨 核心修正：利用 SQL 的原子性直接加减 (credit_score = credit_score + ?)，防止并发读写引发的分数覆盖
        boolean success = userService.update(new LambdaUpdateWrapper<User>()
                .eq(User::getUserId, userId)
                .setSql("credit_score = credit_score + " + scoreChange));

        if (!success) throw new BusinessException("信誉分干预失败");
        return Result.success(null, "信誉分人工干预已生效");
    }
}