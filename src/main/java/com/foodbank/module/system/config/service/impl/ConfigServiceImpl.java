package com.foodbank.module.system.config.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.mapper.GoodsMapper;
import com.foodbank.module.system.config.entity.Config;
import com.foodbank.module.system.config.mapper.ConfigMapper;
import com.foodbank.module.system.config.model.dto.ConfigUpdateDTO;
import com.foodbank.module.system.config.service.IConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
public class ConfigServiceImpl extends ServiceImpl<ConfigMapper, Config> implements IConfigService {

    @Autowired
    private GoodsMapper goodsMapper;

    private static final Set<String> VALID_MODES = Set.of("NORMAL", "WARNING_FREEZE", "EMERGENCY_RESPONSE", "RECOVERY");

    @Override
    public Config getCurrentConfig() {
        Config config = this.getById(1);
        if (config == null) {
            throw new BusinessException("调度引擎配置未初始化，请检查数据库");
        }
        return config;
    }

    @Override
    public void updateEngineConfig(ConfigUpdateDTO dto) {
        BigDecimal total = dto.getWDist()
                .add(dto.getWUrgency())
                .add(dto.getWCredit())
                .add(dto.getWTag());

        if (total.compareTo(new BigDecimal("1.00")) != 0) {
            throw new BusinessException("参数错误：各项权重总和必须等于 1.00 (100%)");
        }

        Config config = new Config();
        BeanUtils.copyProperties(dto, config);
        config.setId(1);

        this.updateById(config);
    }

    @Override
    public void switchMode(String targetMode, Long operatorId) {
        if (targetMode == null || !VALID_MODES.contains(targetMode)) {
            throw new BusinessException("非法模式: " + targetMode + ", 合法值: " + VALID_MODES);
        }

        Config config = this.getById(1);
        if (config == null) {
            throw new BusinessException("系统配置未初始化");
        }

        String currentMode = config.getSysMode() == null ? "NORMAL" : config.getSysMode();
        if (currentMode.equals(targetMode)) {
            throw new BusinessException("系统已处于 " + targetMode + " 模式, 无需重复切换");
        }

        // 状态机合法性校验
        validateTransition(currentMode, targetMode);

        config.setSysMode(targetMode);
        config.setModeActivatedAt(LocalDateTime.now());
        config.setModeActivatedBy(operatorId);

        // SAW 权重自动联动
        applyPresetWeights(config, targetMode);

        this.updateById(config);

        log.info("【应急状态机】 系统模式跃迁: {} → {}, 权重已自动同步, 操作人ID: {}", currentMode, targetMode, operatorId);
    }

    private void applyPresetWeights(Config config, String mode) {
        switch (mode) {
            case "NORMAL":
                config.setWDist(new BigDecimal("0.80"));
                config.setWUrgency(new BigDecimal("0.05"));
                config.setWCredit(new BigDecimal("0.05"));
                config.setWTag(new BigDecimal("0.10"));
                break;
            case "WARNING_FREEZE":
                config.setWDist(new BigDecimal("0.45"));
                config.setWUrgency(new BigDecimal("0.25"));
                config.setWCredit(new BigDecimal("0.10"));
                config.setWTag(new BigDecimal("0.20"));
                break;
            case "EMERGENCY_RESPONSE":
                config.setWDist(new BigDecimal("0.30"));
                config.setWUrgency(new BigDecimal("0.40"));
                config.setWCredit(new BigDecimal("0.05"));
                config.setWTag(new BigDecimal("0.25"));
                break;
            case "RECOVERY":
                config.setWDist(new BigDecimal("0.60"));
                config.setWUrgency(new BigDecimal("0.15"));
                config.setWCredit(new BigDecimal("0.10"));
                config.setWTag(new BigDecimal("0.15"));
                break;
        }
    }

    @Override
    public long countEmergencyGoods() {
        return goodsMapper.selectCount(new LambdaQueryWrapper<Goods>()
                .eq(Goods::getIsEmergencyOnly, 1)
                .gt(Goods::getStock, 0));
    }

    private void validateTransition(String from, String to) {
        boolean valid = switch (from) {
            case "NORMAL" -> "WARNING_FREEZE".equals(to);
            case "WARNING_FREEZE" -> "EMERGENCY_RESPONSE".equals(to) || "NORMAL".equals(to);
            case "EMERGENCY_RESPONSE" -> "RECOVERY".equals(to);
            case "RECOVERY" -> "NORMAL".equals(to);
            default -> false;
        };

        if (!valid) {
            throw new BusinessException("状态机违规: 不允许从 " + from + " 直接跃迁到 " + to);
        }
    }
}