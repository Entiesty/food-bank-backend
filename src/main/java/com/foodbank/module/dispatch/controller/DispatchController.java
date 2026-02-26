package com.foodbank.module.dispatch.controller;

import com.foodbank.common.api.Result;
import com.foodbank.module.dispatch.model.dto.DispatchReqDTO;
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

    @Operation(summary = "发起智能派单计算", description = "基于LBS与多因子的派单引擎，返回评分排序后的候选据点")
    @PostMapping("/smart-match")
    public Result<List<DispatchCandidateVO>> smartMatch(@Validated @RequestBody DispatchReqDTO reqDTO) {
        // 调用我们刚刚写好的流水线服务
        List<DispatchCandidateVO> bestStations = dispatchOrderService.smartMatchStations(reqDTO);
        return Result.success(bestStations);
    }

    @Operation(summary = "1. 志愿者抢单接口", description = "利用 CAS 机制处理高并发抢单，防止超卖")
    @PostMapping("/grab")
    public Result<String> grabOrder(
            @Parameter(description = "订单ID", required = true) @RequestParam Long orderId,
            @Parameter(description = "志愿者ID", required = true) @RequestParam Long volunteerId) {

        dispatchOrderService.grabOrder(orderId, volunteerId);
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