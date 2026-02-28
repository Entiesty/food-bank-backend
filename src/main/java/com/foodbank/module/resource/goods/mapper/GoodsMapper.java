package com.foodbank.module.resource.goods.mapper;

import com.foodbank.module.resource.goods.entity.Goods;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 物资库存与流转表 Mapper 接口
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {

}
