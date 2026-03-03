package com.foodbank.module.system.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.foodbank.module.system.config.entity.Config;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConfigMapper extends BaseMapper<Config> {
}