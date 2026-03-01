package com.foodbank.module.volunteer.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.common.api.Result;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.system.user.entity.CreditLog;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.ICreditLogService;
import com.foodbank.module.system.user.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "Volunteer Controller", description = "志愿者专属中心：信誉与荣誉")
@RestController
@RequestMapping("/volunteer/credit")
public class VolunteerController {

    @Autowired
    private IUserService userService;
    @Autowired
    private ICreditLogService creditLogService;

    @Operation(summary = "1. 获取当前志愿者的信誉分看板数据")
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> getCreditDashboard() {
        Long userId = UserContext.getUserId();
        User user = userService.getById(userId);

        int currentScore = user.getCreditScore() != null ? user.getCreditScore() : 100;

        // 计算击败了全城多少百分比的志愿者 (极其真实的业务逻辑)
        long totalVolunteers = userService.count(new LambdaQueryWrapper<User>().eq(User::getRole, 3));
        long lowerScoreCount = userService.count(new LambdaQueryWrapper<User>()
                .eq(User::getRole, 3)
                .lt(User::getCreditScore, currentScore));

        int beatPercentage = totalVolunteers <= 1 ? 100 : (int) ((lowerScoreCount * 100) / totalVolunteers);

        Map<String, Object> data = new HashMap<>();
        data.put("creditScore", currentScore);
        data.put("beatPercentage", beatPercentage);
        data.put("levelName", getLevelName(currentScore));
        return Result.success(data);
    }

    @Operation(summary = "2. 获取信誉分明细流水")
    @GetMapping("/logs")
    public Result<Page<CreditLog>> getCreditLogs(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Long userId = UserContext.getUserId();
        LambdaQueryWrapper<CreditLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CreditLog::getUserId, userId)
                .orderByDesc(CreditLog::getCreateTime);

        return Result.success(creditLogService.page(new Page<>(pageNum, pageSize), queryWrapper));
    }

    // 动态称号引擎
    private String getLevelName(Integer score) {
        if (score < 120) return "青铜微光";
        if (score < 200) return "白银先锋";
        if (score < 300) return "黄金卫士";
        return "钻石守护者"; // 顶级称号
    }
}