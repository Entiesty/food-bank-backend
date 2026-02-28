package com.foodbank.module.trade.order.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;

public interface IDispatchOrderService extends IService<DispatchOrder> {

    /**
     * 受赠方发布紧急求助/物资需求
     */
    void publishDemandOrder(DemandPublishDTO dto);

}