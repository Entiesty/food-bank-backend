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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;

@Tag(name = "Dispatch Controller", description = "核心智能调度指令接口")
@RestController
@RequestMapping("/dispatch")
public class DispatchController {

    @Autowired
    private DispatchEngineServiceImpl dispatchOrderService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${foodbank.dispatch.fallback-threshold:30}")
    private Integer fallbackThreshold;

    @Operation(summary = "获取调度中心动态配置", description = "读取运力熔断的超时阈值")
    @GetMapping("/config")
    public Result<Integer> getDispatchConfig() {
        return Result.success(fallbackThreshold);
    }

    @Operation(summary = "0. 模拟智能派单计算(答辩演示专用)", description = "直接输入经纬度和需求，不落库，直接返回算法打分与排序结果")
    @PostMapping("/smart-match")
    public Result<List<DispatchCandidateVO>> smartMatch(@Validated @RequestBody DemandPublishDTO reqDTO) {
        // 将前端传来的 DTO 组装成临时的 Order 对象喂给底层算法引擎
        DispatchOrder tempDispatchOrder = new DispatchOrder();
        tempDispatchOrder.setTargetLon(reqDTO.getTargetLon());
        tempDispatchOrder.setTargetLat(reqDTO.getTargetLat());
        tempDispatchOrder.setRequiredCategory(reqDTO.getRequiredCategory());
        tempDispatchOrder.setUrgencyLevel(reqDTO.getUrgencyLevel().byteValue());

        // 🚨 核心修复：将 List<String> 类型的标签，用逗号拼接成 String 后再存入实体！
        if (reqDTO.getRequiredTags() != null && !reqDTO.getRequiredTags().isEmpty()) {
            tempDispatchOrder.setRequiredTags(String.join(",", reqDTO.getRequiredTags()));
        } else {
            tempDispatchOrder.setRequiredTags(null);
        }

        // 配送方式透传
        tempDispatchOrder.setDeliveryMethod(reqDTO.getDeliveryMethod() != null ? reqDTO.getDeliveryMethod().byteValue() : (byte)1);

        // 调用流水线服务，直接返回各种因子的打分明细和最优路径
        List<DispatchCandidateVO> bestStations = dispatchOrderService.smartMatchStations(tempDispatchOrder);
        return Result.success(bestStations);
    }

    @Operation(summary = "1. 志愿者抢单接口", description = "利用 CAS 机制处理高并发抢单")
    @PostMapping("/grab")
    public Result<String> grabOrder(
            @Parameter(description = "订单ID", required = true) @RequestParam Long orderId) {
        Long myVolunteerId = UserContext.getUserId();
        dispatchOrderService.grabOrder(orderId, myVolunteerId);
        return Result.success("抢单成功！请尽快前往据点取货");
    }

    @Operation(summary = "2. 志愿者确认取货接口", description = "利用 @Version 乐观锁防止重复提交")
    @PostMapping("/pickup")
    public Result<String> pickUpGoods(
            @Parameter(description = "任务ID", required = true) @RequestParam Long taskId) {
        dispatchOrderService.pickUpGoods(taskId);
        return Result.success("取货成功！请注意派送安全");
    }

    @Operation(summary = "触发周边商铺紧急定向募捐", description = "基于LBS寻找3公里内商家，支持动态扩圈降级")
    @PostMapping("/emergency/broadcast/{orderId}")
    public Result<Map<String, Object>> triggerEmergencyBroadcast(@PathVariable Long orderId) {
        // 接收降级策略返回的复杂对象
        Map<String, Object> response = dispatchOrderService.triggerEmergencyBroadcast(orderId);
        return Result.success(response);
    }

    // 🚨 新增：商家轮询接收广播接口 (阅后即焚机制)
    @Operation(summary = "商家轮询接收紧急广播", description = "获取后立刻从Redis中删除该消息，防止重复弹窗")
    @GetMapping("/emergency/my-broadcast")
    public Result<Map<String, String>> checkMyBroadcast() {
        Long myUserId = UserContext.getUserId(); // 获取当前登录的商家ID
        String redisKey = "EMERGENCY_BCAST:" + myUserId;

        // 去 Redis 查有没有指挥中心发给我的消息
        String msg = stringRedisTemplate.opsForValue().get(redisKey);

        if (msg != null) {
            // 🚀 阅后即焚：查到就立马删掉，保证商家只弹窗一次
            stringRedisTemplate.delete(redisKey);

            String[] parts = msg.split("\\|");
            Map<String, String> data = new HashMap<>();
            data.put("category", parts[0]);
            data.put("orderId", parts[1]);
            return Result.success(data);
        }

        // 没消息就返回 null，前端静默不处理
        return Result.success(null);
    }
}