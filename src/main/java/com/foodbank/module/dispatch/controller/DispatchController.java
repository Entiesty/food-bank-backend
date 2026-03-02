package com.foodbank.module.dispatch.controller;

import com.foodbank.common.api.Result;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;
import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import com.foodbank.module.dispatch.service.impl.DispatchEngineServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.beans.factory.annotation.Value; // 🚨 引入 Value 注解
import org.springframework.web.bind.annotation.GetMapping; // 🚨 引入 GetMapping

@Tag(name = "Dispatch Controller", description = "核心智能调度指令接口")
@RestController
@RequestMapping("/dispatch")
public class DispatchController {

    @Autowired
    private DispatchEngineServiceImpl dispatchOrderService;

    // 🚨 1. 读取 yaml 中的配置，如果 yaml 没写，默认给个 30 秒兜底
    @Value("${foodbank.dispatch.fallback-threshold:30}")
    private Integer fallbackThreshold;

    // 🚨 2. 新增一个接口，把阈值下发给前端大屏
    @Operation(summary = "获取调度中心动态配置", description = "读取运力熔断的超时阈值")
    @GetMapping("/config")
    public Result<Integer> getDispatchConfig() {
        return Result.success(fallbackThreshold);
    }

    @Operation(summary = "0. 模拟智能派单计算(答辩演示专用)", description = "直接输入经纬度和需求，不落库，直接返回算法打分与排序结果")
    @PostMapping("/smart-match")
    public Result<List<DispatchCandidateVO>> smartMatch(@Validated @RequestBody DemandPublishDTO reqDTO) {
        // 🚨 将前端传来的 DTO 组装成临时的 Order 对象，适配我们升级后的引擎
        DispatchOrder tempDispatchOrder = new DispatchOrder();
        tempDispatchOrder.setTargetLon(reqDTO.getTargetLon());
        tempDispatchOrder.setTargetLat(reqDTO.getTargetLat());
        tempDispatchOrder.setRequiredCategory(reqDTO.getRequiredCategory());
        tempDispatchOrder.setUrgencyLevel(reqDTO.getUrgencyLevel().byteValue());

        // 调用流水线服务，直接返回各种因子的打分明细
        List<DispatchCandidateVO> bestStations = dispatchOrderService.smartMatchStations(tempDispatchOrder);
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