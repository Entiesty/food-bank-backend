package com.foodbank.module.system.config.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.foodbank.module.system.config.entity.Config;
import com.foodbank.module.system.config.model.dto.ConfigUpdateDTO;

public interface IConfigService extends IService<Config> {
    Config getCurrentConfig();
    void updateEngineConfig(ConfigUpdateDTO dto);
}