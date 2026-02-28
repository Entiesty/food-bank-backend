package com.foodbank.module.trade.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.service.IStationService;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.order.mapper.DispatchOrderMapper;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;
import com.foodbank.module.trade.order.model.vo.AvailableOrderVO;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DispatchOrderServiceImpl extends ServiceImpl<DispatchOrderMapper, DispatchOrder> implements IDispatchOrderService {

    @Autowired
    private IStationService stationService; // 🚨 注入据点服务，用于关联查询

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishDemandOrder(DemandPublishDTO dto) {
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            throw new BusinessException("用户信息获取失败，请重新登录");
        }

        DispatchOrder dispatchOrder = new DispatchOrder();
        dispatchOrder.setOrderSn("REQ" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        dispatchOrder.setOrderType((byte) 2);
        dispatchOrder.setDestId(currentUserId);
        dispatchOrder.setRequiredCategory(dto.getRequiredCategory());
        dispatchOrder.setUrgencyLevel(dto.getUrgencyLevel().byteValue());
        dispatchOrder.setTargetLon(dto.getTargetLon());
        dispatchOrder.setTargetLat(dto.getTargetLat());
        dispatchOrder.setStatus((byte) 0);

        boolean saved = this.save(dispatchOrder);
        if (!saved) {
            throw new BusinessException("求助发布失败，请稍后重试");
        }
    }

    @Override
    public Page<AvailableOrderVO> getAvailableOrderPage(int pageNum, int pageSize) {
        // 1. 分页查询状态为 1 (调度中/待抢单) 的订单
        Page<DispatchOrder> pageReq = new Page<>(pageNum, pageSize);
        Page<DispatchOrder> orderPage = this.page(pageReq, new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, 1)
                .isNotNull(DispatchOrder::getSourceId) // 必须是调度引擎已经分配了起点的单子
                .orderByDesc(DispatchOrder::getUrgencyLevel) // 🚨 越紧急的越排在最前面
                .orderByDesc(DispatchOrder::getCreateTime));

        // 2. 将实体转化为 VO 试图对象，拼装上据点详细信息
        List<AvailableOrderVO> voList = orderPage.getRecords().stream().map(order -> {
            Station station = stationService.getById(order.getSourceId());
            return AvailableOrderVO.builder()
                    .orderId(order.getOrderId())
                    .requiredCategory(order.getRequiredCategory())
                    .urgencyLevel(order.getUrgencyLevel())
                    .targetLon(order.getTargetLon())
                    .targetLat(order.getTargetLat())
                    .sourceStationId(station != null ? station.getStationId() : null)
                    .sourceStationName(station != null ? station.getStationName() : "未知据点")
                    .sourceStationAddress(station != null ? station.getAddress() : "未知地址")
                    .sourceLon(station != null ? station.getLongitude() : null)
                    .sourceLat(station != null ? station.getLatitude() : null)
                    .createTime(order.getCreateTime())
                    .build();
        }).collect(Collectors.toList());

        // 3. 构建新的分页返回对象
        Page<AvailableOrderVO> resultPage = new Page<>(pageNum, pageSize, orderPage.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }
}