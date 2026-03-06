package com.foodbank.module.trade.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.service.IStationService;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.IUserService;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.order.mapper.DispatchOrderMapper;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;
import com.foodbank.module.trade.order.model.vo.AvailableOrderVO;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DispatchOrderServiceImpl extends ServiceImpl<DispatchOrderMapper, DispatchOrder> implements IDispatchOrderService {

    @Autowired
    private IStationService stationService;

    @Autowired
    private IUserService userService;

    private void enrichOrderNames(List<DispatchOrder> orders) {
        if (orders == null || orders.isEmpty()) return;
        for (DispatchOrder order : orders) {
            if (order.getOrderType() != null && order.getOrderType() == 1) {
                // 🔵 捐赠单 (起点是商家，终点是驿站)
                if (order.getSourceId() != null) {
                    User merchant = userService.getById(order.getSourceId());
                    if (merchant != null) {
                        order.setSourceName(merchant.getUsername() + " (爱心商铺)");
                        // 🚨 补齐商家坐标，剔除幽灵偏移
                        order.setSourceLon(merchant.getCurrentLon());
                        order.setSourceLat(merchant.getCurrentLat());
                    }
                }
                if (order.getDestId() != null) {
                    Station station = stationService.getById(order.getDestId());
                    if (station != null) order.setTargetName(station.getStationName());
                }
            } else {
                // 🔴 求助单 (起点是驿站，终点是市民)
                if (order.getSourceId() != null) {
                    Station station = stationService.getById(order.getSourceId());
                    if (station != null) {
                        order.setSourceName(station.getStationName());
                        // 🚨 补齐驿站坐标
                        order.setSourceLon(station.getLongitude());
                        order.setSourceLat(station.getLatitude());
                    }
                }
                if (order.getDestId() != null) {
                    User recipient = userService.getById(order.getDestId());
                    if (recipient != null) order.setTargetName(recipient.getUsername() + " (求助市民)");
                }
            }
        }
    }

    @Override
    public List<DispatchOrder> getPendingOrdersForMap() {
        // 🚨 核心修复：大屏只查状态为 0 (待接单) 的纯净新单
        // 并且排除掉已经被管理员转为自提 (deliveryMethod = 2) 的单子
        List<DispatchOrder> list = this.list(new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, (byte) 0)
                .ne(DispatchOrder::getDeliveryMethod, (byte) 2)
                .orderByDesc(DispatchOrder::getCreateTime));

        // 调用翻译官，塞入真实名字！
        enrichOrderNames(list);
        return list;
    }

    @Override
    public Page<DispatchOrder> getAdminOrderPage(int pageNum, int pageSize, String orderSn, Byte status, Byte deliveryMethod) {
        Page<DispatchOrder> pageReq = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<DispatchOrder> wrapper = new LambdaQueryWrapper<>();

        if (orderSn != null && !orderSn.trim().isEmpty()) {
            wrapper.like(DispatchOrder::getOrderSn, orderSn);
        }
        if (status != null) {
            wrapper.eq(DispatchOrder::getStatus, status);
        }
        if (deliveryMethod != null) {
            wrapper.eq(DispatchOrder::getDeliveryMethod, deliveryMethod);
        }
        wrapper.orderByDesc(DispatchOrder::getCreateTime);

        Page<DispatchOrder> page = this.page(pageReq, wrapper);

        // 🚨 给管理员的大盘也调用翻译官，补齐起终点名字！
        enrichOrderNames(page.getRecords());

        return page;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishDemandOrder(DemandPublishDTO dto) {
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) throw new BusinessException("用户信息获取失败，请重新登录");

        DispatchOrder dispatchOrder = new DispatchOrder();
        dispatchOrder.setOrderSn("SOS-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        dispatchOrder.setOrderType((byte) 2);
        dispatchOrder.setDestId(currentUserId);
        dispatchOrder.setRequiredCategory(dto.getRequiredCategory());
        dispatchOrder.setUrgencyLevel(dto.getUrgencyLevel().byteValue());
        dispatchOrder.setTargetLon(dto.getTargetLon());
        dispatchOrder.setTargetLat(dto.getTargetLat());
        dispatchOrder.setStatus((byte) 0);

        String specificName = dto.getDescription() != null ? dto.getDescription() : dto.getRequiredCategory();
        dispatchOrder.setGoodsName("急需：" + specificName);
        dispatchOrder.setGoodsCount(1);

        boolean saved = this.save(dispatchOrder);
        if (!saved) throw new BusinessException("求助发布失败，请稍后重试");
    }

    @Override
    public Page<AvailableOrderVO> getAvailableOrderPage(int pageNum, int pageSize) {
        // ... (保持你原有的逻辑不变，AvailableOrderVO 里面已经做好映射了) ...
        Page<DispatchOrder> pageReq = new Page<>(pageNum, pageSize);
        Page<DispatchOrder> orderPage = this.page(pageReq, new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, 0) // 🚨 必须是 0 (待抢单/待处理)
                .isNotNull(DispatchOrder::getSourceId)
                .orderByDesc(DispatchOrder::getUrgencyLevel)
                .orderByDesc(DispatchOrder::getCreateTime));

        List<AvailableOrderVO> voList = orderPage.getRecords().stream().map(order -> {
            String sourceName = "未知起点";
            String sourceAddress = "位置待确认";
            BigDecimal sourceLon = null;
            BigDecimal sourceLat = null;
            String targetName = "未知终点";
            String targetAddress = "位置待确认";
            BigDecimal targetLon = order.getTargetLon();
            BigDecimal targetLat = order.getTargetLat();

            if (order.getOrderType() != null && order.getOrderType() == 1) {
                User merchant = userService.getById(order.getSourceId());
                if (merchant != null) {
                    sourceName = merchant.getUsername() + " (爱心商铺)";
                    sourceAddress = "联系电话: " + merchant.getPhone();
                    sourceLon = merchant.getCurrentLon();
                    sourceLat = merchant.getCurrentLat();
                }
                Station station = stationService.getById(order.getDestId());
                if (station != null) {
                    targetName = station.getStationName();
                    targetAddress = station.getAddress();
                    targetLon = station.getLongitude();
                    targetLat = station.getLatitude();
                }
            } else {
                Station station = stationService.getById(order.getSourceId());
                if (station != null) {
                    sourceName = station.getStationName();
                    sourceAddress = station.getAddress();
                    sourceLon = station.getLongitude();
                    sourceLat = station.getLatitude();
                }
                User recipient = userService.getById(order.getDestId());
                if (recipient != null) {
                    targetName = recipient.getUsername() + " (求助市民)";
                    targetAddress = "联系电话: " + recipient.getPhone();
                    if (targetLon == null) targetLon = recipient.getCurrentLon();
                    if (targetLat == null) targetLat = recipient.getCurrentLat();
                }
            }
            return AvailableOrderVO.builder().orderId(order.getOrderId()).orderSn(order.getOrderSn()).goodsName(order.getGoodsName()).goodsCount(order.getGoodsCount()).requiredCategory(order.getRequiredCategory()).urgencyLevel(order.getUrgencyLevel()).sourceName(sourceName).sourceAddress(sourceAddress).sourceLon(sourceLon).sourceLat(sourceLat).targetName(targetName).targetAddress(targetAddress).targetLon(targetLon).targetLat(targetLat).createTime(order.getCreateTime()).build();
        }).collect(Collectors.toList());

        Page<AvailableOrderVO> resultPage = new Page<>(pageNum, pageSize, orderPage.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void switchOrderToPickup(Long orderId) {
        DispatchOrder order = this.getById(orderId);
        if (order == null || order.getStatus() == 2 || order.getStatus() == 3) {
            throw new BusinessException("订单状态异常或已被处理，无法转为自提");
        }
        order.setDeliveryMethod((byte) 2);
        boolean updated = this.updateById(order);
        if (!updated) throw new BusinessException("运力熔断触发失败，数据库更新异常");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        DispatchOrder order = this.getById(orderId);
        if (order == null) throw new BusinessException("订单不存在");
        if (order.getStatus() >= 2) throw new BusinessException("志愿者已送达或订单已完成，无法撤销");

        order.setStatus((byte) 3);
        boolean updated = this.updateById(order);
        if (!updated) throw new BusinessException("撤销失败，请重试");
    }
}