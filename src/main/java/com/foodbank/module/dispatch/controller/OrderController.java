package com.foodbank.module.dispatch.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodbank.common.api.Result;
import com.foodbank.module.dispatch.entity.Order;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;
import com.foodbank.module.dispatch.service.IOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 双向物流调度订单表 前端控制器
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Tag(name = "Order Controller", description = "调度订单查询与管理")
@RestController
@RequestMapping("/dispatch/order")
public class OrderController {

    @Autowired
    private IOrderService orderService;

    @Operation(summary = "获取大屏待抢订单", description = "只查询状态为 0 (待匹配) 的订单，防止出现幽灵订单")
    @GetMapping("/pending-list")
    public Result<List<Order>> getPendingOrders() {

        // 🚨 核心防线：用 LambdaQueryWrapper 严格限制只查询 status = 0 的订单
        LambdaQueryWrapper<Order> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Order::getStatus, 0); // 0代表待匹配

        // 按创建时间倒序排，让最新发出的求助单显示在最前面（可选）
        queryWrapper.orderByDesc(Order::getCreateTime);

        List<Order> pendingList = orderService.list(queryWrapper);

        return Result.success(pendingList);
    }

    @Operation(summary = "受赠方发布紧急求助/物资需求")
    @PostMapping("/publish-demand")
    public Result<Void> publishDemand(@Validated @RequestBody DemandPublishDTO dto) {
        orderService.publishDemandOrder(dto);
        return Result.success(null, "求助信息已发布，系统正在为您智能匹配物资...");
    }
}