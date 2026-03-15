package com.foodbank.module.system.user.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.model.dto.PasswordUpdateDTO;
import com.foodbank.module.system.user.model.dto.UserUpdateDTO;
import com.foodbank.module.system.user.model.vo.UserDashboardVO;
import com.foodbank.module.system.user.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "User Center", description = "用户个人中心与安全设置")
@RestController
@RequestMapping("/system/user")
public class UserController {

    @Autowired
    private IUserService userService;

    @Operation(summary = "1. 获取当前登录用户信息")
    @GetMapping("/profile")
    public Result<User> getMyProfile() {
        Long userId = UserContext.getUserId();
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException("用户信息不存在");
        }
        user.setPassword(null);
        return Result.success(user);
    }

    @Operation(summary = "2. 更新个人基本信息及各类角色专有档案")
    @PutMapping("/profile")
    public Result<Void> updateProfile(@Validated @RequestBody UserUpdateDTO dto) {
        Long userId = UserContext.getUserId();

        LambdaUpdateWrapper<User> updateWrapper = new LambdaUpdateWrapper<User>()
                .eq(User::getUserId, userId)
                .set(User::getUsername, dto.getUsername());

        // 1. 通用 LBS 坐标更新
        if (dto.getCurrentLon() != null && dto.getCurrentLat() != null) {
            updateWrapper.set(User::getCurrentLon, dto.getCurrentLon())
                    .set(User::getCurrentLat, dto.getCurrentLat());
        }

        // 2. 核心凭证与状态回退机制
        if (dto.getIdentityProofUrl() != null && !dto.getIdentityProofUrl().isEmpty()) {
            updateWrapper.set(User::getIdentityProofUrl, dto.getIdentityProofUrl());
            // 🚨 核心风控：只要动了资料/重新传了图，立刻打回待审核状态，防止作弊
            updateWrapper.set(User::getIsVerified, (byte) 0);
        }

        // 3. 志愿者专属字段：运力载具
        if (dto.getVehicleType() != null) {
            updateWrapper.set(User::getVehicleType, dto.getVehicleType());
        }

        // 4. 受赠方专属字段：门牌号与健康信息
        if (dto.getDoorNumber() != null) {
            updateWrapper.set(User::getDoorNumber, dto.getDoorNumber());
        }
        if (dto.getEmergencyPhone() != null) {
            updateWrapper.set(User::getEmergencyPhone, dto.getEmergencyPhone());
        }
        if (dto.getHealthRemark() != null) {
            updateWrapper.set(User::getHealthRemark, dto.getHealthRemark());
        }

        boolean success = userService.update(updateWrapper);
        if (!success) throw new BusinessException("资料更新失败，请重试");

        return Result.success(null, "资料已保存至系统，并提交至风控审核队列！");
    }

    @Operation(summary = "3. 修改密码")
    @PutMapping("/password")
    public Result<Void> updatePassword(@Validated @RequestBody PasswordUpdateDTO dto) {
        Long userId = UserContext.getUserId();
        User user = userService.getById(userId);

        String oldMd5 = DigestUtils.md5DigestAsHex(dto.getOldPassword().getBytes());
        if (!user.getPassword().equals(oldMd5)) {
            throw new BusinessException("旧密码不正确，请重新输入");
        }

        String newMd5 = DigestUtils.md5DigestAsHex(dto.getNewPassword().getBytes());
        userService.update(new LambdaUpdateWrapper<User>()
                .eq(User::getUserId, userId)
                .set(User::getPassword, newMd5));

        return Result.success(null, "密码修改成功，下次请使用新密码登录");
    }

    @Operation(summary = "4. 获取资质审核列表 (管理员端)")
    @GetMapping("/admin/audit-page")
    public Result<Page<User>> getAuditPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Byte role,
            @RequestParam(required = false) Byte isVerified) {
        return Result.success(userService.getAuditPage(pageNum, pageSize, role, isVerified));
    }

    @Operation(summary = "5. 提交资质审核结果 (管理员端)")
    @PutMapping("/admin/audit/{userId}")
    public Result<String> submitAudit(@PathVariable Long userId, @RequestParam boolean isPass) {
        userService.auditUser(userId, isPass);
        return Result.success(null, isPass ? "审核已通过，该用户已获得平台信任背书！" : "已驳回审核，系统将要求用户重新上传材料。");
    }

    @Operation(summary = "6. 获取千人千面大盘看板数据", description = "用于个人中心展示各类角色的核心成就")
    @GetMapping("/dashboard/stats")
    public Result<UserDashboardVO> getDashboardStats() {
        Long userId = UserContext.getUserId();
        return Result.success(userService.getUserDashboardStats(userId));
    }

    @Operation(summary = "7. 更新用户头像", description = "接收前端传来的 MinIO 文件 URL")
    @PutMapping("/avatar")
    public Result<Void> updateAvatar(@RequestParam String avatarUrl) {
        Long userId = UserContext.getUserId();
        boolean success = userService.update(new LambdaUpdateWrapper<User>()
                .eq(User::getUserId, userId)
                .set(User::getAvatar, avatarUrl));
        if (!success) {
            throw new BusinessException("头像更新失败，请重试");
        }
        return Result.success(null, "头像更换成功！");
    }

    @Operation(summary = "获取可选的据点负责人列表")
    @GetMapping("/managers")
    public Result<List<User>> getEligibleManagers() {
        return Result.success(userService.getEligibleManagers());
    }
}