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

    private static final Set<String> VALID_MODES = Set.of("NORMAL", "EMERGENCY");

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
        Config config = this.getById(1);
        if (config == null) throw new BusinessException("系统配置未初始化");

        BigDecimal wDist = dto.getWDist() != null ? dto.getWDist() : config.getWDist();
        BigDecimal wUrgency = dto.getWUrgency() != null ? dto.getWUrgency() : config.getWUrgency();
        BigDecimal wCredit = dto.getWCredit() != null ? dto.getWCredit() : config.getWCredit();
        BigDecimal wTag = dto.getWTag() != null ? dto.getWTag() : config.getWTag();
        BigDecimal wExpiration = dto.getWExpiration() != null ? dto.getWExpiration() : config.getWExpiration();
        BigDecimal wStock = dto.getWStock() != null ? dto.getWStock() : config.getWStock();

        BigDecimal total = wDist.add(wUrgency).add(wCredit).add(wTag).add(wExpiration).add(wStock);
        if (total.compareTo(new BigDecimal("1.00")) != 0) {
            throw new BusinessException("参数错误：六维权重总和必须等于 1.00 (当前=" + total + ")");
        }

        // wTimeCoin is independent of the six-factor sum (volunteer-only bonus)
        if (dto.getWTimeCoin() != null) config.setWTimeCoin(dto.getWTimeCoin());

        config.setWDist(wDist);
        config.setWUrgency(wUrgency);
        config.setWCredit(wCredit);
        config.setWTag(wTag);
        config.setWExpiration(wExpiration);
        config.setWStock(wStock);
        if (dto.getSysMode() != null) config.setSysMode(dto.getSysMode());

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
        // 六维SAW权重预设 (wDist + wUrgency + wCredit + wTag + wExpiration + wStock = 1.00)
        // wTimeCoin 独立于六维体系, 仅在志愿者抢单路径生效
        switch (mode) {
            case "NORMAL":
                config.setWDist(new BigDecimal("0.35"));
                config.setWUrgency(new BigDecimal("0.20"));
                config.setWCredit(new BigDecimal("0.15"));
                config.setWTag(new BigDecimal("0.15"));
                config.setWExpiration(new BigDecimal("0.10"));
                config.setWStock(new BigDecimal("0.05"));
                config.setWTimeCoin(new BigDecimal("0.05"));
                break;
            case "EMERGENCY":
                config.setWDist(new BigDecimal("0.10"));
                config.setWUrgency(new BigDecimal("0.45"));
                config.setWCredit(new BigDecimal("0.05"));
                config.setWTag(new BigDecimal("0.25"));
                config.setWExpiration(new BigDecimal("0.05"));
                config.setWStock(new BigDecimal("0.10"));
                config.setWTimeCoin(new BigDecimal("0.15"));
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
        // 双轨双态: NORMAL ↔ EMERGENCY 直接切换
        boolean valid = ("NORMAL".equals(from) && "EMERGENCY".equals(to))
                     || ("EMERGENCY".equals(from) && "NORMAL".equals(to));
        if (!valid) {
            throw new BusinessException("状态机违规: 仅允许 NORMAL ↔ EMERGENCY 直接切换");
        }
    }
}