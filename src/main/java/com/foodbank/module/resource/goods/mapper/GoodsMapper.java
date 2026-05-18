package com.foodbank.module.resource.goods.mapper;

import com.foodbank.module.resource.goods.entity.Goods;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {
    List<Goods> selectAvailableGoodsByStation(@Param("stationId") Long stationId, @Param("categories") List<String> categories, @Param("sysMode") String sysMode);

    BigDecimal getMerchantTotalValue(@Param("merchantId") Long merchantId);
}