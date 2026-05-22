package com.foodbank.module.system.config.controller;

import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.system.config.entity.Config;
import com.foodbank.module.system.config.model.dto.ConfigUpdateDTO;
import com.foodbank.module.system.config.model.dto.SwitchModeDTO;
import com.foodbank.module.system.config.service.IConfigService;
import com.foodbank.websocket.WebSocketServer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Config Controller", description = "调度引擎底层权重参数配置")
@RestController
@RequestMapping("/system/config")
public class ConfigController {

    @Autowired
    private IConfigService configService;

    @Operation(summary = "1. 获取当前系统引擎配置")
    @GetMapping("/current")
    public Result<Config> getCurrentConfig() {
        return Result.success(configService.getCurrentConfig());
    }

    @Operation(summary = "2. 热更新系统引擎配置")
    @PutMapping("/update")
    public Result<Void> updateConfig(@Validated @RequestBody ConfigUpdateDTO dto) {
        Byte role = UserContext.getUserRole();
        if (role == null || role != 4) {
            throw new BusinessException("越权操作：仅限管理员修改调度参数");
        }

        configService.updateEngineConfig(dto);
        return Result.success(null, "调度参数已更新");
    }

    @Operation(summary = "3. 应急状态机切换 (NORMAL → WARNING_FREEZE → EMERGENCY_RESPONSE → RECOVERY → NORMAL)")
    @PutMapping("/switch-mode")
    public Result<String> switchMode(@Validated @RequestBody SwitchModeDTO dto) {
        Byte role = UserContext.getUserRole();
        if (role == null || role != 4) {
            throw new BusinessException("越权操作：仅限管理员切换应急模式");
        }

        Long operatorId = UserContext.getUserId();
        configService.switchMode(dto.getTargetMode(), operatorId);

        // ✅ FIX-1: 全局WebSocket广播模式切换, 所有在线客户端实时同步
        String broadcastMsg = "{\"type\":\"MODE_CHANGED\",\"mode\":\"" + dto.getTargetMode() + "\"}";
        WebSocketServer.broadcast(broadcastMsg);

        return Result.success(dto.getTargetMode(), "系统模式已切换至: " + dto.getTargetMode());
    }

    @Operation(summary = "4. 战备物资存量预检 (切换 WARNING_FREEZE 前调用)")
    @GetMapping("/pre-check")
    public Result<java.util.Map<String, Object>> preCheckMode(@RequestParam String mode) {
        Byte role = UserContext.getUserRole();
        if (role == null || role != 4) {
            throw new BusinessException("越权操作");
        }
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        if ("WARNING_FREEZE".equals(mode)) {
            long emergencyCount = configService.countEmergencyGoods();
            result.put("emergencyCount", emergencyCount);
            result.put("warning", emergencyCount == 0 ? "当前无战备物资，进入预警冻结将导致所有日常物资从调度列表隐退" : null);
        }
        return Result.success(result);
    }
}