package com.foodbank.module.trade.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodbank.common.api.Result;
import com.foodbank.common.api.ResultCode;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
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
public class DispatchOrderController {

    @Autowired
    private IDispatchOrderService orderService;

    @Operation(summary = "获取大屏待抢订单", description = "只查询状态为 0 (待匹配) 的订单，防止出现幽灵订单")
    @GetMapping("/pending-list")
    public Result<List<DispatchOrder>> getPendingOrders() {

        // 🚨 核心防线：用 LambdaQueryWrapper 严格限制只查询 status = 0 的订单
        LambdaQueryWrapper<DispatchOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DispatchOrder::getStatus, 0); // 0代表待匹配

        // 按创建时间倒序排，让最新发出的求助单显示在最前面（可选）
        queryWrapper.orderByDesc(DispatchOrder::getCreateTime);

        List<DispatchOrder> pendingList = orderService.list(queryWrapper);

        return Result.success(pendingList);
    }

    @Operation(summary = "受赠方发布紧急求助/物资需求")
    @PostMapping("/publish-demand")
    public Result<Void> publishDemand(@Validated @RequestBody DemandPublishDTO dto) {
        // 🚨 RBAC 拦截防线：如果不是受赠方(1)或管理员(4)，直接踢出去！
        Byte role = UserContext.getUserRole();
        if (role != 1 && role != 4) {
            throw new BusinessException(ResultCode.FORBIDDEN, "越权操作：只有受赠方可以发布求助！");
        }

        orderService.publishDemandOrder(dto);
        return Result.success(null, "求助信息已发布，系统正在为您智能匹配物资...");
    }
}