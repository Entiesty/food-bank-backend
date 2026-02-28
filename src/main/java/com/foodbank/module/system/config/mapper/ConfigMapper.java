package com.foodbank.module.system.config.mapper;

import com.foodbank.module.system.config.entity.Config;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 系统动态权重配置表 Mapper 接口
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Mapper
public interface ConfigMapper extends BaseMapper<Config> {

}
