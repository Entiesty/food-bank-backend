package com.foodbank.module.dispatch.mapper;

import com.foodbank.module.dispatch.entity.Order;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 双向物流调度订单表 Mapper 接口
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

}
