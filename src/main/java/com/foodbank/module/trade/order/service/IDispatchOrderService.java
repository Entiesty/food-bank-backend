package com.foodbank.module.trade.order.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;
import com.foodbank.module.trade.order.model.vo.AvailableOrderVO;
import java.util.List;

public interface IDispatchOrderService extends IService<DispatchOrder> {
    String publishDemandOrder(DemandPublishDTO dto);
    Page<AvailableOrderVO> getAvailableOrderPage(int pageNum, int pageSize);
    void switchOrderToPickup(Long orderId);
    Page<DispatchOrder> getAdminOrderPage(int pageNum, int pageSize, String orderSn, Byte status, Byte deliveryMethod);
    void cancelOrder(Long orderId);
    List<DispatchOrder> getPendingOrdersForMap();
    void confirmReceiptAndRate(Long orderId, Long userId, Integer rating, String comment);

    // 🚀 万能自提验码核销（管理员/商家可用）
    void verifyPickupCode(String pickupCode);
}