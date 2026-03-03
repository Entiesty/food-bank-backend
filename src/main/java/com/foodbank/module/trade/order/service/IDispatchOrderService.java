package com.foodbank.module.trade.order.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;
import com.foodbank.module.trade.order.model.vo.AvailableOrderVO;

public interface IDispatchOrderService extends IService<DispatchOrder> {

    /**
     * 受赠方发布紧急求助/物资需求
     */
    void publishDemandOrder(DemandPublishDTO dto);

    /**
     * 🚀 新增：获取待抢单的分页列表 (抢单大厅)
     */
    Page<AvailableOrderVO> getAvailableOrderPage(int pageNum, int pageSize);

    /**
     * 🚀 运力熔断：一键转自提
     */
    void switchOrderToPickup(Long orderId);

    /**
     * 获取全盘订单流转列表 (支持多条件复杂检索)
     */
    Page<DispatchOrder> getAdminOrderPage(int pageNum, int pageSize, String orderSn, Byte status, Byte deliveryMethod);
}