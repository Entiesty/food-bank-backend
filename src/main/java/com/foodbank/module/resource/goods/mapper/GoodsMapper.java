package com.foodbank.module.resource.goods.mapper;

import com.foodbank.module.resource.goods.entity.Goods;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {
    // 自定义查询：加速驿站可用物资的检索
    List<Goods> selectAvailableGoodsByStation(@Param("stationId") Long stationId, @Param("categories") List<String> categories);
}