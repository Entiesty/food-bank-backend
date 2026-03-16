package com.foodbank.module.trade.order.mapper;

import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface DispatchOrderMapper extends BaseMapper<DispatchOrder> {
    // 自定义查询：获取所有无驿站(点对点)的待处理商家供应单，作为 L0 级雷达源
    List<DispatchOrder> selectPendingDirectSupplyOrders(@Param("categories") List<String> categories);
}