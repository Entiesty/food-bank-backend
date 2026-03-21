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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Order Controller", description = "调度订单查询与管理")
@RestController
@RequestMapping("/trade/order")
public class DispatchOrderController {

    @Autowired
    private IDispatchOrderService orderService;

    @Operation(summary = "获取大屏待处理订单")
    @GetMapping("/pending-list")
    public Result<List<DispatchOrder>> getPendingOrders() {
        return Result.success(orderService.getPendingOrdersForMap());
    }

    @Operation(summary = "发布物资求助单/自提单")
    @PostMapping("/publish")
    public Result<Map<String, String>> publishDemand(@RequestBody DemandPublishDTO dto) {
        // 调用 Service 获取生成的取件码
        String code = orderService.publishDemandOrder(dto);

        // 包装成 JSON 对象返回给前端
        Map<String, String> map = new HashMap<>();
        map.put("pickupCode", code);

        return Result.success(map);
    }

    @Operation(summary = "志愿者抢单大厅列表")
    @GetMapping("/available-list")
    public Result<Page<AvailableOrderVO>> getAvailableOrders(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Byte role = UserContext.getUserRole();
        if (role != null && role != 3 && role != 4) {
            throw new BusinessException("越权访问：仅限志愿者访问抢单大厅");
        }
        return Result.success(orderService.getAvailableOrderPage(pageNum, pageSize));
    }

    @Operation(summary = "运力熔断：一键转自提")
    @PostMapping("/switch-pickup")
    public Result<Void> switchOrderToPickup(@RequestParam Long orderId) {
        Byte role = UserContext.getUserRole();
        if (role != null && role != 4) {
            throw new BusinessException("越权操作：仅指挥中心可触发运力熔断机制");
        }
        orderService.switchOrderToPickup(orderId);
        return Result.success(null, "系统已成功触发熔断，订单转为自提模式");
    }

    @Operation(summary = "全盘订单流转 (管理员视角)")
    @GetMapping("/admin-page")
    public Result<Page<DispatchOrder>> getAdminOrderPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String orderSn,
            @RequestParam(required = false) Byte status,
            @RequestParam(required = false) Byte deliveryMethod) {
        Byte role = UserContext.getUserRole();
        if (role != null && role != 4) {
            throw new BusinessException("越权访问：仅限系统管理员查看大账本");
        }
        return Result.success(orderService.getAdminOrderPage(pageNum, pageSize, orderSn, status, deliveryMethod));
    }

    @Operation(summary = "受赠方查询我的实时求助状态")
    @GetMapping("/my-active-sos")
    public Result<DispatchOrder> getMyActiveSos() {
        Long userId = UserContext.getUserId();
        Byte role = UserContext.getUserRole();
        if (role != null && role != 1) {
            throw new BusinessException("操作失败：仅受赠方可查询专属求助状态");
        }
        LambdaQueryWrapper<DispatchOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DispatchOrder::getDestId, userId);
        queryWrapper.in(DispatchOrder::getStatus, 0, 1);
        queryWrapper.orderByDesc(DispatchOrder::getCreateTime);
        queryWrapper.last("LIMIT 1");
        return Result.success(orderService.getOne(queryWrapper));
    }

    @Operation(summary = "受赠方查询历史求助档案")
    @GetMapping("/my-history")
    public Result<Page<DispatchOrder>> getMyHistoryOrders(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String status) { // 🚨 1. 新增：接收前端传来的 status 字符串

        Long userId = UserContext.getUserId();
        Byte role = UserContext.getUserRole();
        if (role != null && role != 1) {
            throw new BusinessException("操作失败：仅受赠方可访问求助档案");
        }

        Page<DispatchOrder> pageReq = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<DispatchOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DispatchOrder::getDestId, userId);

        // 🚨 2. 核心修复：动态解析状态参数并构建 SQL
        if (status != null && !status.trim().isEmpty()) {
            // 将前端传来的 "0,1" 等字符串按逗号切分
            String[] statusArray = status.split(",");
            java.util.List<Integer> statusList = new java.util.ArrayList<>();
            for (String s : statusArray) {
                statusList.add(Integer.parseInt(s.trim()));
            }
            // 组装 SQL，例如：WHERE status IN (0, 1)
            queryWrapper.in(DispatchOrder::getStatus, statusList);
        }

        // 按创建时间倒序排列
        queryWrapper.orderByDesc(DispatchOrder::getCreateTime);

        return Result.success(orderService.page(pageReq, queryWrapper));
    }

    @Operation(summary = "撤销订单")
    @PutMapping("/cancel/{orderId}")
    public Result<Void> cancelDemandOrder(@PathVariable Long orderId) {
        Byte role = UserContext.getUserRole();
        if (role != null && role != 1 && role != 4) {
            throw new BusinessException("越权操作：仅受赠方或指挥中心可撤销订单");
        }
        orderService.cancelOrder(orderId);
        return Result.success(null, "订单已成功强制撤销");
    }

    @Operation(summary = "指挥中心：获取异常滞留订单大屏数据")
    @GetMapping("/exception-monitor")
    public Result<List<DispatchOrder>> getExceptionMonitorList() {
        Byte role = UserContext.getUserRole();
        if (role != null && role != 4) {
            throw new BusinessException("越权访问：仅限指挥中心访问预警大屏");
        }
        List<DispatchOrder> exceptionList = orderService.list(new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, 0)
                .isNotNull(DispatchOrder::getExceptionReason)
                .orderByAsc(DispatchOrder::getCreateTime));
        return Result.success(exceptionList);
    }

    @Operation(summary = "受赠方确认收货并评价")
    @PostMapping("/confirm-receipt")
    public Result<String> confirmReceipt(
            @RequestParam Long orderId,
            @RequestParam Integer rating,
            @RequestParam(required = false) String comment) {
        Long myUserId = UserContext.getUserId();
        orderService.confirmReceiptAndRate(orderId, myUserId, rating, comment);
        return Result.success("评价成功，感谢您的反馈！");
    }

    @Operation(summary = "通用线下自提验码核销")
    @PostMapping("/verify-pickup")
    public Result<String> verifyPickupCode(@RequestParam String pickupCode) {
        orderService.verifyPickupCode(pickupCode);
        return Result.success("核销成功！物资已在线下交接完毕。");
    }

    @GetMapping("/distribution/{goodsId}")
    public Result<List<Map<String, Object>>> getGoodsDistribution(@PathVariable Long goodsId) {
        return Result.success(orderService.getGoodsDistributionDetails(goodsId));
    }
}