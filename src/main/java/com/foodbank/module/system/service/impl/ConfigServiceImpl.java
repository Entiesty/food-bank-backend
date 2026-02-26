package com.foodbank.module.system.service.impl;

import com.foodbank.module.system.entity.Config;
import com.foodbank.module.system.mapper.ConfigMapper;
import com.foodbank.module.system.service.IConfigService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 系统动态权重配置表 服务实现类
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Service
public class ConfigServiceImpl extends ServiceImpl<ConfigMapper, Config> implements IConfigService {

}
