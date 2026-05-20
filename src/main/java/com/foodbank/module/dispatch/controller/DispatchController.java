package com.foodbank.module.dispatch.controller;

import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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
        // ✅ FIX-3: 补齐鉴权 — 拒绝未登录匿名调用
        Long userId = UserContext.getUserId();
        if (userId == null) throw new BusinessException("请先登录后再使用智能撮合引擎");

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

    // 🚨 持久化广播：商家轮询获取所有未处理的紧急广播列表 (不再阅后即焚)
    @Operation(summary = "商家轮询接收紧急广播列表", description = "持久化广播，仅商家成功接单后才清除对应orderId")
    @GetMapping("/emergency/my-broadcast")
    public Result<java.util.List<Map<String, String>>> checkMyBroadcast() {
        // 平时态不返回紧急广播，前端雷达页处于休眠状态
        String sysMode = dispatchOrderService.getCurrentSystemMode();
        if (!"EMERGENCY".equals(sysMode)) {
            return Result.success(new java.util.ArrayList<>());
        }

        Long myUserId = UserContext.getUserId();
        String pattern = "EMERGENCY_BCAST:" + myUserId + ":*";

        java.util.Set<String> keys = stringRedisTemplate.keys(pattern);
        java.util.List<Map<String, String>> list = new java.util.ArrayList<>();

        if (keys != null && !keys.isEmpty()) {
            for (String key : keys) {
                String msg = stringRedisTemplate.opsForValue().get(key);
                if (msg == null) continue;
                String[] parts = msg.split("\\|");
                Map<String, String> data = new HashMap<>();
                data.put("category", parts[0]);
                data.put("orderId", parts[1]);
                if (parts.length > 2) data.put("recipientName", parts[2]);
                if (parts.length > 3) data.put("recipientTag", parts[3]);
                if (parts.length > 4) data.put("doorNumber", parts[4]);
                if (parts.length > 5) data.put("urgency", parts[5]);
                if (parts.length > 6) data.put("lon", parts[6]);
                if (parts.length > 7) data.put("lat", parts[7]);
                list.add(data);
            }
            log.info("📡 商家 {} 轮询拉取到 {} 条紧急广播", myUserId, list.size());
        }

        return Result.success(list);
    }
}