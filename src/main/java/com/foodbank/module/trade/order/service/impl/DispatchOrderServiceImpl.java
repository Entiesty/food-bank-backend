package com.foodbank.module.trade.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.dispatch.strategy.MultiFactorDispatchStrategy;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.service.IStationService;
import com.foodbank.module.system.user.entity.CreditLog;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.ICreditLogService;
import com.foodbank.module.system.user.service.IUserService;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.order.mapper.DispatchOrderMapper;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;
import com.foodbank.module.trade.order.model.vo.AvailableOrderVO;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.trade.task.service.IDeliveryTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DispatchOrderServiceImpl extends ServiceImpl<DispatchOrderMapper, DispatchOrder> implements IDispatchOrderService {

    @Autowired
    private IStationService stationService;

    @Autowired
    private IUserService userService;

    @Autowired
    private MultiFactorDispatchStrategy dispatchStrategy;

    @Autowired
    private IDeliveryTaskService taskService;

    @Autowired
    private ICreditLogService creditLogService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.foodbank.module.resource.goods.service.IGoodsService goodsService;

    private void enrichOrderNames(List<DispatchOrder> orders) {
        if (orders == null || orders.isEmpty()) return;
        for (DispatchOrder order : orders) {
            if (order.getOrderType() != null && order.getOrderType() == 1) {
                if (order.getSourceId() != null) {
                    User merchant = userService.getById(order.getSourceId());
                    if (merchant != null) {
                        order.setSourceName(merchant.getUsername() + " (爱心商铺)");
                        order.setSourceLon(merchant.getCurrentLon());
                        order.setSourceLat(merchant.getCurrentLat());
                    }
                }
                if (order.getDestId() != null) {
                    Station station = stationService.getById(order.getDestId());
                    if (station != null) order.setTargetName(station.getStationName());
                }
            } else {
                if (order.getSourceId() != null) {
                    Station station = null;
                    if (order.getSourceId() < 0) {
                        User merchant = userService.getById(-order.getSourceId());
                        if (merchant != null) {
                            order.setSourceName(merchant.getUsername() + " (爱心商铺直发)");
                            order.setSourceLon(merchant.getCurrentLon());
                            order.setSourceLat(merchant.getCurrentLat());
                        }
                    } else {
                        station = stationService.getById(order.getSourceId());
                        if (station != null) {
                            order.setSourceName(station.getStationName());
                            order.setSourceLon(station.getLongitude());
                            order.setSourceLat(station.getLatitude());
                        }
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
        List<DispatchOrder> list = this.list(new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, (byte) 0)
                .ne(DispatchOrder::getDeliveryMethod, (byte) 2)
                .orderByDesc(DispatchOrder::getCreateTime));
        enrichOrderNames(list);
        return list;
    }

    @Override
    public Page<DispatchOrder> getAdminOrderPage(int pageNum, int pageSize, String orderSn, Byte status, Byte deliveryMethod) {
        Page<DispatchOrder> pageReq = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<DispatchOrder> wrapper = new LambdaQueryWrapper<>();
        if (orderSn != null && !orderSn.trim().isEmpty()) wrapper.like(DispatchOrder::getOrderSn, orderSn);
        if (status != null) wrapper.eq(DispatchOrder::getStatus, status);
        if (deliveryMethod != null) wrapper.eq(DispatchOrder::getDeliveryMethod, deliveryMethod);
        wrapper.orderByDesc(DispatchOrder::getCreateTime);

        Page<DispatchOrder> page = this.page(pageReq, wrapper);
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
        dispatchOrder.setRequiredTags(dto.getRequiredTags() != null ? String.join(",", dto.getRequiredTags()) : null);
        dispatchOrder.setUrgencyLevel(dto.getUrgencyLevel() != null ? dto.getUrgencyLevel().byteValue() : 1);
        dispatchOrder.setTargetLon(dto.getTargetLon());
        dispatchOrder.setTargetLat(dto.getTargetLat());

        dispatchOrder.setDeliveryMethod(dto.getDeliveryMethod() != null ? dto.getDeliveryMethod().byteValue() : (byte) 1);

        if (dispatchOrder.getDeliveryMethod() == 2 && dto.getGoodsId() != null) {
            // 【原子库存预扣】
            com.foodbank.module.resource.goods.entity.Goods goods = goodsService.getById(dto.getGoodsId());
            if (goods == null || goods.getStock() < 1) {
                throw new BusinessException("手慢了！该物资已被其他街坊抢空了！");
            }
            goods.setStock(goods.getStock() - 1);
            if (goods.getStock() == 0) goods.setStatus((byte) 3);
            goodsService.updateById(goods);

            String code = String.valueOf((int)((Math.random() * 9 + 1) * 100000));
            dispatchOrder.setPickupCode(code);
            dispatchOrder.setGoodsId(dto.getGoodsId());
            dispatchOrder.setSourceId(dto.getSourceId());
            dispatchOrder.setStatus((byte) 1); // 越过0，直接待取货
        } else {
            dispatchOrder.setStatus((byte) 0);
        }

        String specificName = dto.getDescription() != null ? dto.getDescription() : dto.getRequiredCategory();
        dispatchOrder.setGoodsName("急需：" + specificName);
        dispatchOrder.setGoodsCount(1);

        boolean saved = this.save(dispatchOrder);
        if (!saved) throw new BusinessException("求助发布失败，请稍后重试");
    }

    @Override
    public Page<AvailableOrderVO> getAvailableOrderPage(int pageNum, int pageSize) {
        Page<DispatchOrder> pageReq = new Page<>(pageNum, pageSize);
        // 拦截自提单与脏数据
        Page<DispatchOrder> orderPage = this.page(pageReq, new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, 0)
                .eq(DispatchOrder::getDeliveryMethod, 1)
                .isNotNull(DispatchOrder::getSourceId)
                .and(w -> w.isNotNull(DispatchOrder::getDestId).or().isNotNull(DispatchOrder::getTargetLon))
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
                if (order.getSourceId() < 0) {
                    User merchant = userService.getById(-order.getSourceId());
                    if (merchant != null) {
                        sourceName = merchant.getUsername() + " (爱心商铺直发)";
                        sourceAddress = "联系电话: " + merchant.getPhone();
                        sourceLon = merchant.getCurrentLon();
                        sourceLat = merchant.getCurrentLat();
                    }
                } else {
                    Station station = stationService.getById(order.getSourceId());
                    if (station != null) {
                        sourceName = station.getStationName();
                        sourceAddress = station.getAddress();
                        sourceLon = station.getLongitude();
                        sourceLat = station.getLatitude();
                    }
                }

                User recipient = userService.getById(order.getDestId());
                if (recipient != null) {
                    targetName = recipient.getUsername() + " (求助市民)";
                    targetAddress = "联系电话: " + recipient.getPhone();
                    if (targetLon == null) targetLon = recipient.getCurrentLon();
                    if (targetLat == null) targetLat = recipient.getCurrentLat();
                }
            }
            return AvailableOrderVO.builder()
                    .orderId(order.getOrderId()).orderSn(order.getOrderSn())
                    .goodsName(order.getGoodsName()).goodsCount(order.getGoodsCount())
                    .requiredCategory(order.getRequiredCategory()).urgencyLevel(order.getUrgencyLevel())
                    .sourceName(sourceName).sourceAddress(sourceAddress).sourceLon(sourceLon).sourceLat(sourceLat)
                    .targetName(targetName).targetAddress(targetAddress).targetLon(targetLon).targetLat(targetLat)
                    .createTime(order.getCreateTime()).build();
        }).collect(Collectors.toList());

        Long userId = UserContext.getUserId();
        User volunteer = userService.getById(userId);
        if (volunteer != null) {
            Double volLon = volunteer.getCurrentLon() != null ? volunteer.getCurrentLon().doubleValue() : null;
            Double volLat = volunteer.getCurrentLat() != null ? volunteer.getCurrentLat().doubleValue() : null;
            int creditScore = volunteer.getCreditScore() != null ? volunteer.getCreditScore() : 100;
            dispatchStrategy.rankOrdersForVolunteer(voList, volLon, volLat, creditScore);
        }

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

        // 【库存兜底回滚】
        if (order.getDeliveryMethod() != null && order.getDeliveryMethod() == 2 && order.getGoodsId() != null) {
            com.foodbank.module.resource.goods.entity.Goods goods = goodsService.getById(order.getGoodsId());
            if (goods != null) {
                goods.setStock(goods.getStock() + 1);
                if (goods.getStatus() == 3) goods.setStatus((byte) 2);
                goodsService.updateById(goods);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceiptAndRate(Long orderId, Long userId, Integer rating, String comment) {
        DispatchOrder order = this.getById(orderId);
        if (order == null || !order.getDestId().equals(userId)) {
            throw new BusinessException("非法操作：订单不存在或您不是该订单的受赠方");
        }
        if (order.getStatus() != 2) throw new BusinessException("订单当前状态无法确认收货");

        order.setStatus((byte) 3);
        order.setRecipientRating(rating);
        order.setRecipientComment(comment);
        this.updateById(order);

        DeliveryTask task = taskService.getOne(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getOrderId, orderId)
                .eq(DeliveryTask::getTaskStatus, 3)
                .last("LIMIT 1"));

        if (task != null) {
            Long volunteerId = task.getVolunteerId();
            int creditDelta = 0;
            String reason = "订单完结评价: " + rating + "星";

            if (rating == 5) creditDelta = 5;
            else if (rating == 4) creditDelta = 2;
            else if (rating == 2) creditDelta = -5;
            else if (rating == 1) creditDelta = -15;

            if (creditDelta != 0) {
                User volunteer = userService.getById(volunteerId);
                if (volunteer != null) {
                    int oldScore = volunteer.getCreditScore() != null ? volunteer.getCreditScore() : 0;
                    volunteer.setCreditScore(oldScore + creditDelta);
                    userService.updateById(volunteer);

                    CreditLog creditLog = new CreditLog();
                    creditLog.setUserId(volunteerId);
                    creditLog.setOrderId(orderId);
                    creditLog.setChangeValue(creditDelta);
                    creditLog.setReason(reason + (comment != null ? " (" + comment + ")" : ""));
                    creditLog.setCreateTime(LocalDateTime.now());
                    creditLogService.save(creditLog);
                }
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void verifyPickupCode(String pickupCode) {
        Long verifierId = UserContext.getUserId();
        User verifier = userService.getById(verifierId);

        if (verifier.getRole() == null || (verifier.getRole() != 2 && verifier.getRole() != 4)) {
            throw new BusinessException("权限不足：仅系统管理员(网格员)或商家可执行线下核销！");
        }

        DispatchOrder order = this.getOne(new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getPickupCode, pickupCode)
                .eq(DispatchOrder::getDeliveryMethod, 2));

        if (order == null) throw new BusinessException("取件码错误或该订单不存在！");
        if (order.getStatus() == 3) throw new BusinessException("该取件码已被核销过，请勿重复操作！");
        if (order.getStatus() == 0) throw new BusinessException("物资还在准备中，暂不能核销！");

        order.setStatus((byte) 3);
        this.updateById(order);

        int oldScore = verifier.getCreditScore() != null ? verifier.getCreditScore() : 0;
        verifier.setCreditScore(oldScore + 2);
        userService.updateById(verifier);

        CreditLog creditLog = new CreditLog();
        creditLog.setUserId(verifierId);
        creditLog.setOrderId(order.getOrderId());
        creditLog.setChangeValue(2);
        creditLog.setReason("协助处理线下扫码自提业务");
        creditLog.setCreateTime(LocalDateTime.now());
        creditLogService.save(creditLog);
    }
}