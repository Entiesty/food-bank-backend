package com.foodbank.module.trade.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.service.IStationService;
import com.foodbank.module.system.user.entity.CreditLog;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.trade.task.mapper.DeliveryTaskMapper;
import com.foodbank.module.system.user.service.ICreditLogService;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import com.foodbank.module.trade.task.model.vo.MyTaskVO;
import com.foodbank.module.trade.task.service.IDeliveryTaskService;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DeliveryTaskServiceImpl extends ServiceImpl<DeliveryTaskMapper, DeliveryTask> implements IDeliveryTaskService {

    @Autowired
    private IDispatchOrderService orderService;
    @Autowired
    private IUserService userService;
    @Autowired
    private ICreditLogService creditLogService;
    @Autowired
    private IStationService stationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeTask(Long taskId, Long userId) {
        DeliveryTask deliveryTask = this.getById(taskId);
        if (deliveryTask == null) {
            throw new BusinessException("未找到该配送任务");
        }
        if (!deliveryTask.getVolunteerId().equals(userId)) {
            throw new BusinessException("权限不足：您不是该任务的执行人");
        }

        if (deliveryTask.getTaskStatus() == 3) {
            throw new BusinessException("该任务已经核销完毕，请勿重复操作");
        }

        deliveryTask.setTaskStatus((byte) 3);
        deliveryTask.setCompleteTime(LocalDateTime.now());
        this.updateById(deliveryTask);

        DispatchOrder dispatchOrder = orderService.getById(deliveryTask.getOrderId());
        if (dispatchOrder != null) {
            dispatchOrder.setStatus((byte) 2);
            orderService.updateById(dispatchOrder);
        }

        rewardVolunteerCredit(userId, deliveryTask.getOrderId());
    }

    private void rewardVolunteerCredit(Long userId, Long orderId) {
        int rewardPoints = 10;
        User user = userService.getById(userId);

        if (user != null && user.getRole() != null && user.getRole() == 3) {
            int oldScore = user.getCreditScore() != null ? user.getCreditScore() : 0;
            user.setCreditScore(oldScore + rewardPoints);
            userService.updateById(user);

            CreditLog creditLog = new CreditLog();
            creditLog.setUserId(userId);
            creditLog.setOrderId(orderId);
            creditLog.setChangeValue(rewardPoints);
            creditLog.setReason("完成订单送达，发放积分奖励");
            creditLog.setCreateTime(LocalDateTime.now());
            creditLogService.save(creditLog);

            log.info("志愿者[{}]完成配送，信誉分增加{}，当前总分:{}", userId, rewardPoints, user.getCreditScore());
        }
    }

    @Override
    public Page<MyTaskVO> getMyTasksPage(Long volunteerId, Byte status, int pageNum, int pageSize) {
        LambdaQueryWrapper<DeliveryTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DeliveryTask::getVolunteerId, volunteerId);
        if (status != null) {
            queryWrapper.eq(DeliveryTask::getTaskStatus, status);
        }
        queryWrapper.orderByDesc(DeliveryTask::getAcceptTime);

        Page<DeliveryTask> taskPage = this.page(new Page<>(pageNum, pageSize), queryWrapper);

        List<MyTaskVO> voList = taskPage.getRecords().stream().map(task -> {
            DispatchOrder order = orderService.getById(task.getOrderId());

            // 初始化起终点信息
            String sourceName = "未知起点";
            String sourceAddress = "位置待确认";
            BigDecimal sourceLon = null;
            BigDecimal sourceLat = null;

            String targetName = "未知终点";
            String targetAddress = "位置待确认";
            BigDecimal targetLon = null;
            BigDecimal targetLat = null;

            if (order != null) {
                targetLon = order.getTargetLon();
                targetLat = order.getTargetLat();

                // 🚨 核心逻辑：红蓝双轨分流判断
                if (order.getOrderType() != null && order.getOrderType() == 1) {
                    // 【🔵 捐赠单】 起点(Source)=商家，终点(Dest)=据点
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
                    // 【🔴 求助单】 起点(Source)=据点，终点(Dest)=受助市民
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
                        // 如果订单没存坐标，用用户表里的实时坐标兜底
                        if (targetLon == null) targetLon = recipient.getCurrentLon();
                        if (targetLat == null) targetLat = recipient.getCurrentLat();
                    }
                }
            }

            return MyTaskVO.builder()
                    .taskId(task.getTaskId())
                    .taskStatus(task.getTaskStatus())
                    .acceptTime(task.getAcceptTime())
                    .orderId(task.getOrderId())
                    .orderSn(order != null ? order.getOrderSn() : null)
                    .goodsName(order != null ? order.getGoodsName() : null)
                    .goodsCount(order != null ? order.getGoodsCount() : null)
                    .urgencyLevel(order != null ? order.getUrgencyLevel() : null)
                    .requiredCategory(order != null ? order.getRequiredCategory() : "未知")

                    // 🚨 注入统一抽象后的起终点数据
                    .sourceName(sourceName)
                    .sourceAddress(sourceAddress)
                    .sourceLon(sourceLon)
                    .sourceLat(sourceLat)
                    .targetName(targetName)
                    .targetAddress(targetAddress)
                    .targetLon(targetLon)
                    .targetLat(targetLat)

                    .build();
        }).collect(Collectors.toList());

        Page<MyTaskVO> resultPage = new Page<>(pageNum, pageSize, taskPage.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }
}