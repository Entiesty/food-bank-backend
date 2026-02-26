package com.foodbank.module.dispatch.service.impl;

import com.foodbank.module.dispatch.entity.Order;
import com.foodbank.module.dispatch.mapper.OrderMapper;
import com.foodbank.module.dispatch.service.IOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 双向物流调度订单表 服务实现类
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

}
