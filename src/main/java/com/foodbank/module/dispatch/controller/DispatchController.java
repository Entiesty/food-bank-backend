package com.foodbank.module.dispatch.controller;

import com.foodbank.common.api.Result;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.dispatch.entity.Order;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;
import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import com.foodbank.module.dispatch.service.impl.DispatchOrderServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Dispatch Controller", description = "核心智能调度指令接口")
@RestController
@RequestMapping("/dispatch")
public class DispatchController {

    @Autowired
    private DispatchOrderServiceImpl dispatchOrderService;

    @Operation(summary = "0. 模拟智能派单计算(答辩演示专用)", description = "直接输入经纬度和需求，不落库，直接返回算法打分与排序结果")
    @PostMapping("/smart-match")
    public Result<List<DispatchCandidateVO>> smartMatch(@Validated @RequestBody DemandPublishDTO reqDTO) {
        // 🚨 将前端传来的 DTO 组装成临时的 Order 对象，适配我们升级后的引擎
        Order tempOrder = new Order();
        tempOrder.setTargetLon(reqDTO.getTargetLon());
        tempOrder.setTargetLat(reqDTO.getTargetLat());
        tempOrder.setRequiredCategory(reqDTO.getRequiredCategory());
        tempOrder.setUrgencyLevel(reqDTO.getUrgencyLevel().byteValue());

        // 调用流水线服务，直接返回各种因子的打分明细
        List<DispatchCandidateVO> bestStations = dispatchOrderService.smartMatchStations(tempOrder);
        return Result.success(bestStations);
    }

    @Operation(summary = "1. 志愿者抢单接口", description = "利用 CAS 机制处理高并发抢单，利用 UserContext 实现安全防篡改")
    @PostMapping("/grab")
    public Result<String> grabOrder(
            @Parameter(description = "订单ID", required = true) @RequestParam Long orderId) {

        // 核心爽点：不再信任前端传来的 volunteerId，直接从底层拦截器解析出的 Token 中安全提取！
        Long myVolunteerId = UserContext.getUserId();

        dispatchOrderService.grabOrder(orderId, myVolunteerId);
        return Result.success("抢单成功！请尽快前往据点取货");
    }

    @Operation(summary = "2. 志愿者确认取货接口", description = "利用 @Version 乐观锁防止网络卡顿导致的重复提交")
    @PostMapping("/pickup")
    public Result<String> pickUpGoods(
            @Parameter(description = "任务ID", required = true) @RequestParam Long taskId) {

        dispatchOrderService.pickUpGoods(taskId);
        return Result.success("取货成功！请注意派送安全");
    }
}