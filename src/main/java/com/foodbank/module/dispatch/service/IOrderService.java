package com.foodbank.module.dispatch.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.foodbank.module.dispatch.entity.Order;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;

public interface IOrderService extends IService<Order> {

    /**
     * 受赠方发布紧急求助/物资需求
     */
    void publishDemandOrder(DemandPublishDTO dto);

}