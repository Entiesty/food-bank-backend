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
@RequestMapping("/trade/order")
public class DispatchOrderController {

    @Autowired
    private IDispatchOrderService orderService;

    @Operation(summary = "获取大屏待处理订单", description = "全量获取待匹配的求助单(SOS)与捐赠入库单(DON)")
    @GetMapping("/pending-list")
    public Result<List<DispatchOrder>> getPendingOrders() {
        // 🚨 终极核心修复：不再直接用 Mybatis-Plus 去查数据库！
        // 而是调用我们在 Service 中写好的 getPendingOrdersForMap() 方法。
        // 这个方法在返回数据前，会触发“翻译官”，把真实的商家名和驿站名拼接到 targetName 和 sourceName 中！
        return Result.success(orderService.getPendingOrdersForMap());
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

    @Operation(summary = "运力熔断：一键转自提", description = "将订单的配送方式从 1(配送) 改为 2(自提)")
    @PostMapping("/switch-pickup")
    public Result<Void> switchOrderToPickup(@RequestParam Long orderId) {
        // 权限校验：只允许管理员(4)触发
        Byte role = UserContext.getUserRole();
        if (role != null && role != 4) {
            throw new BusinessException("越权操作：仅指挥中心可触发运力熔断机制");
        }

        orderService.switchOrderToPickup(orderId);
        return Result.success(null, "系统已成功触发熔断，订单转为自提模式");
    }

    @Operation(summary = "全盘订单流转 (管理员视角)", description = "支持多条件筛选的分页查询所有订单")
    @GetMapping("/admin-page")
    public Result<Page<DispatchOrder>> getAdminOrderPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String orderSn,
            @RequestParam(required = false) Byte status,
            @RequestParam(required = false) Byte deliveryMethod) {

        // 鉴权：只有管理员(Role=4)才能看全盘数据
        Byte role = UserContext.getUserRole();
        if (role != null && role != 4) {
            throw new BusinessException("越权访问：仅限系统管理员查看大账本");
        }

        return Result.success(orderService.getAdminOrderPage(pageNum, pageSize, orderSn, status, deliveryMethod));
    }

    @Operation(summary = "受赠方查询我的实时求助状态", description = "用于受赠方页面轮询当前未完成的求助单进度")
    @GetMapping("/my-active-sos")
    public Result<DispatchOrder> getMyActiveSos() {
        Long userId = UserContext.getUserId();
        Byte role = UserContext.getUserRole();

        if (role != null && role != 1) {
            throw new BusinessException("操作失败：仅受赠方可查询专属求助状态");
        }

        // 使用 MP 原生 getOne 查询当前登录老人的最新一笔未完成订单
        LambdaQueryWrapper<DispatchOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DispatchOrder::getDestId, userId);
        queryWrapper.in(DispatchOrder::getStatus, 0, 1); // 0-待匹配(异常qs池/调度中), 1-派送中
        queryWrapper.orderByDesc(DispatchOrder::getCreateTime);
        queryWrapper.last("LIMIT 1");

        DispatchOrder activeOrder = orderService.getOne(queryWrapper);
        return Result.success(activeOrder);
    }

    @Operation(summary = "受赠方查询历史求助档案", description = "分页查询当前登录老人发起的所有历史订单")
    @GetMapping("/my-history")
    public Result<Page<DispatchOrder>> getMyHistoryOrders(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        Long userId = UserContext.getUserId();
        Byte role = UserContext.getUserRole();

        // 鉴权：严格限制仅受赠方可查
        if (role != null && role != 1) {
            throw new BusinessException("操作失败：仅受赠方可访问求助档案");
        }

        // 构造分页与查询条件
        Page<DispatchOrder> pageReq = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<DispatchOrder> queryWrapper = new LambdaQueryWrapper<>();
        // 查询终点是自己的单子 (作为需求方)
        queryWrapper.eq(DispatchOrder::getDestId, userId);
        // 按时间倒序，最新的在上面
        queryWrapper.orderByDesc(DispatchOrder::getCreateTime);

        return Result.success(orderService.page(pageReq, queryWrapper));
    }

    @Operation(summary = "撤销订单", description = "将未完成的订单状态置为已取消（老人与管理员可用）")
    @PutMapping("/cancel/{orderId}")
    public Result<Void> cancelDemandOrder(@PathVariable Long orderId) {
        Byte role = UserContext.getUserRole();
        // 🚨 核心修复：放行管理员(角色4)的强制取消权限
        if (role != null && role != 1 && role != 4) {
            throw new BusinessException("越权操作：仅受赠方或指挥中心可撤销订单");
        }
        orderService.cancelOrder(orderId);
        return Result.success(null, "订单已成功强制撤销");
    }

    @Operation(summary = "指挥中心：获取异常滞留订单大屏数据", description = "专供异常预警面板轮询")
    @GetMapping("/exception-monitor")
    public Result<List<DispatchOrder>> getExceptionMonitorList() {
        // 鉴权：严格限制仅管理员(4)可访问
        Byte role = UserContext.getUserRole();
        if (role != null && role != 4) {
            throw new BusinessException("越权访问：仅限指挥中心访问预警大屏");
        }

        // 🚨 核心查询：状态为 0 (待匹配)，且 死亡笔记(exceptionReason) 不为空！
        // 用 orderByAsc 按时间正序排，也就是【等待时间最长、最危险】的单子顶在最前面！
        List<DispatchOrder> exceptionList = orderService.list(new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, 0)
                .isNotNull(DispatchOrder::getExceptionReason)
                .orderByAsc(DispatchOrder::getCreateTime));

        return Result.success(exceptionList);
    }

    @Operation(summary = "受赠方确认收货并评价", description = "状态扭转为3，并动态结算骑手信誉分")
    @PostMapping("/confirm-receipt")
    public Result<String> confirmReceipt(
            @RequestParam Long orderId,
            @RequestParam Integer rating,
            @RequestParam(required = false) String comment) {
        Long myUserId = UserContext.getUserId();
        orderService.confirmReceiptAndRate(orderId, myUserId, rating, comment);
        return Result.success("评价成功，感谢您的反馈！");
    }
}