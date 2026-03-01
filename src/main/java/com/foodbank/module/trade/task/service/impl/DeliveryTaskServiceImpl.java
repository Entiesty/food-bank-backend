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
    private IStationService stationService; // 🚨 注入据点服务用于数据拼装

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

        // 🚨 修改点 3：放宽状态机，只要不是已经完成(3)的，都可以直接核销送达
        if (deliveryTask.getTaskStatus() == 3) {
            throw new BusinessException("该任务已经核销完毕，请勿重复操作");
        }

        deliveryTask.setTaskStatus((byte) 3);
        deliveryTask.setCompleteTime(LocalDateTime.now());
        this.updateById(deliveryTask);

        DispatchOrder dispatchOrder = orderService.getById(deliveryTask.getOrderId());
        if (dispatchOrder != null) {
            dispatchOrder.setStatus((byte) 2); // 假设 2 代表订单已被签收
            orderService.updateById(dispatchOrder);
        }

        rewardVolunteerCredit(userId, deliveryTask.getOrderId());
    }

    private void rewardVolunteerCredit(Long userId, Long orderId) {
        // 🚨 修改点 4：与前端黏土风弹窗的 10 分保持一致
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
            Station station = null;
            if (order != null && order.getSourceId() != null) {
                station = stationService.getById(order.getSourceId());
            }
            return MyTaskVO.builder()
                    .taskId(task.getTaskId())
                    .taskStatus(task.getTaskStatus())
                    .acceptTime(task.getAcceptTime())
                    .orderId(task.getOrderId())
                    .requiredCategory(order != null ? order.getRequiredCategory() : "未知")
                    .targetLon(order != null ? order.getTargetLon() : null)
                    .targetLat(order != null ? order.getTargetLat() : null)
                    .stationName(station != null ? station.getStationName() : "未知取货点")
                    .stationAddress(station != null ? station.getAddress() : "未知地址")
                    .build();
        }).collect(Collectors.toList());

        Page<MyTaskVO> resultPage = new Page<>(pageNum, pageSize, taskPage.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }
}