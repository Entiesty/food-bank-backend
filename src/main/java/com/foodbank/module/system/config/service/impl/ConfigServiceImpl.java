package com.foodbank.module.system.config.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.system.config.entity.Config;
import com.foodbank.module.system.config.mapper.ConfigMapper;
import com.foodbank.module.system.config.model.dto.ConfigUpdateDTO;
import com.foodbank.module.system.config.service.IConfigService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class ConfigServiceImpl extends ServiceImpl<ConfigMapper, Config> implements IConfigService {

    @Override
    public Config getCurrentConfig() {
        // 配置表通常只有一条核心数据，直接取 ID = 1
        Config config = this.getById(1);
        if (config == null) {
            throw new BusinessException("调度引擎配置未初始化，请检查数据库");
        }
        return config;
    }

    @Override
    public void updateEngineConfig(ConfigUpdateDTO dto) {
        // 1. 严格校验后端总权重是否等于 1.00 (防篡改)
        BigDecimal total = dto.getWDist()
                .add(dto.getWUrgency())
                .add(dto.getWCredit())
                .add(dto.getWTag());

        if (total.compareTo(new BigDecimal("1.00")) != 0) {
            throw new BusinessException("参数错误：各项权重总和必须等于 1.00 (100%)");
        }

        // 2. 更新 ID=1 的配置记录
        Config config = new Config();
        BeanUtils.copyProperties(dto, config);
        config.setId(1);

        this.updateById(config);
    }
}