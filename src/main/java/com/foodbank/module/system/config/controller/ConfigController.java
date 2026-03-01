package com.foodbank.module.system.config.controller;

import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.system.config.entity.Config;
import com.foodbank.module.system.config.service.IConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Tag(name = "Config Controller", description = "系统算法动态调参控制台")
@RestController
@RequestMapping("/system/config")
public class ConfigController {

    @Autowired
    private IConfigService configService;

    @Operation(summary = "1. 获取当前系统调度权重配置")
    @GetMapping("/current")
    public Result<Config> getCurrentConfig() {
        // 数据库初始化脚本中 id 固定为 1
        Config config = configService.getById(1);
        if (config == null) {
            throw new BusinessException("系统配置丢失，请检查数据库 sys_config 表");
        }
        return Result.success(config);
    }

    @Operation(summary = "2. 动态更新调度权重与模式")
    @PutMapping("/update")
    public Result<Void> updateConfig(@RequestBody Config updateConfig) {
        // 核心校验：保证多因子权重总和严格等于 1.00
        BigDecimal total = updateConfig.getWDist()
                .add(updateConfig.getWUrgency())
                .add(updateConfig.getWCredit())
                .add(updateConfig.getWTag());

        // 考虑到 BigDecimal 的精度，兼容 1.0 和 1.00
        if (total.compareTo(new BigDecimal("1.00")) != 0 && total.compareTo(new BigDecimal("1.0")) != 0) {
            throw new BusinessException("算法调参异常：各项权重加和必须严格等于 1.00（当前总和: " + total + "）");
        }

        updateConfig.setId(1); // 强制覆盖全局唯一记录
        configService.updateById(updateConfig);

        return Result.success(null, "系统算法配置已生效！调度引擎热更新完成。");
    }
}