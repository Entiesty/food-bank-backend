package com.foodbank.module.trade.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.common.api.Result;
import com.foodbank.common.api.ResultCode;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;
import com.foodbank.module.trade.order.model.vo.AvailableOrderVO;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Order Controller", description = "调度订单查询与管理")
@RestController
@RequestMapping("/trade/order") // 🚨 修复路径前缀
public class DispatchOrderController {

    @Autowired
    private IDispatchOrderService orderService;

    @Operation(summary = "获取大屏待抢订单", description = "只查询状态为 0 (待匹配) 的订单，防止出现幽灵订单")
    @GetMapping("/pending-list")
    public Result<List<DispatchOrder>> getPendingOrders() {
        LambdaQueryWrapper<DispatchOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DispatchOrder::getStatus, 0);
        queryWrapper.orderByDesc(DispatchOrder::getCreateTime);
        return Result.success(orderService.list(queryWrapper));
    }

    @Operation(summary = "受赠方发布紧急求助/物资需求")
    @PostMapping("/publish-demand")
    public Result<Void> publishDemand(@Validated @RequestBody DemandPublishDTO dto) {
        Byte role = UserContext.getUserRole();
        if (role != 1 && role != 4) {
            throw new BusinessException(ResultCode.FORBIDDEN, "越权操作：只有受赠方可以发布求助！");
        }
        orderService.publishDemandOrder(dto);
        return Result.success(null, "求助信息已发布，系统正在为您智能匹配物资...");
    }

    @Operation(summary = "志愿者抢单大厅列表", description = "分页获取系统已分配据点但尚未被领取的订单")
    @GetMapping("/available-list")
    public Result<Page<AvailableOrderVO>> getAvailableOrders(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        // 鉴权：只有志愿者(3)和管理员(4)能看抢单大厅
        Byte role = UserContext.getUserRole();
        if (role != null && role != 3 && role != 4) {
            throw new BusinessException("越权访问：仅限志愿者访问抢单大厅");
        }
        return Result.success(orderService.getAvailableOrderPage(pageNum, pageSize));
    }
}