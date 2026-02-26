package com.foodbank.module.station.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.foodbank.module.station.entity.Station;
import org.apache.ibatis.annotations.Mapper;

/**
 * 物资据点 Mapper 接口
 */
@Mapper
public interface StationMapper extends BaseMapper<Station> {
    // 基础的增删改查已经由 BaseMapper 提供
    // 复杂的联表查询或特定的 LBS 空间距离计算 SQL 后续可以在这里定义
}