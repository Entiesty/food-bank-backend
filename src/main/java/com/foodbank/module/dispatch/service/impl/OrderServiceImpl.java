package com.foodbank.module.dispatch.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.dispatch.entity.Order;
import com.foodbank.module.dispatch.mapper.OrderMapper;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;
import com.foodbank.module.dispatch.service.IOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishDemandOrder(DemandPublishDTO dto) {
        // 1. 获取当前登录用户ID
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            throw new BusinessException("用户信息获取失败，请重新登录");
        }

        // 2. 构建业务订单实体
        Order order = new Order();
        // 生成唯一订单号
        order.setOrderSn("REQ" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());

        // 🚨 修正：将 int 字面量强转为 byte
        order.setOrderType((byte) 2); // 2: 需求单

        order.setDestId(currentUserId);
        order.setRequiredCategory(dto.getRequiredCategory());

        // 🚨 修正：调用 byteValue() 将 Integer 转换为 byte
        order.setUrgencyLevel(dto.getUrgencyLevel().byteValue());

        order.setTargetLon(dto.getTargetLon());
        order.setTargetLat(dto.getTargetLat());

        // 🚨 修正：将 int 字面量强转为 byte
        order.setStatus((byte) 0);

        // 3. 存入数据库
        boolean saved = this.save(order);
        if (!saved) {
            throw new BusinessException("求助发布失败，请稍后重试");
        }
    }
}