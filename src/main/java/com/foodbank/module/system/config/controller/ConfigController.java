package com.foodbank.module.system.config.controller;

import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.system.config.entity.Config;
import com.foodbank.module.system.config.model.dto.ConfigUpdateDTO;
import com.foodbank.module.system.config.service.IConfigService;
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
        // 核心风控安全鉴权：必须是管理员 (Role = 4) 才能更改系统运行模式和权重
        Byte role = UserContext.getUserRole();
        if (role == null || role != 4) {
            throw new BusinessException("越权操作警报：仅限 Root 指挥中心管理员修改调度引擎底层参数！");
        }

        configService.updateEngineConfig(dto);
        return Result.success(null, "底层调度引擎参数已热更新完毕，即时生效！");
    }
}