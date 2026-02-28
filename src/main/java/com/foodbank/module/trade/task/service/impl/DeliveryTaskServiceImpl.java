package com.foodbank.module.trade.task.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.system.user.entity.CreditLog;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.trade.task.mapper.DeliveryTaskMapper;
import com.foodbank.module.system.user.service.ICreditLogService;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import com.foodbank.module.trade.task.service.IDeliveryTaskService;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
public class DeliveryTaskServiceImpl extends ServiceImpl<DeliveryTaskMapper, DeliveryTask> implements IDeliveryTaskService {

    @Autowired
    private IDispatchOrderService orderService;
    @Autowired
    private IUserService userService;
    @Autowired
    private ICreditLogService creditLogService;

    @Override
    @Transactional(rollbackFor = Exception.class) // 🚨 保证原子性
    public void completeTask(Long taskId, Long userId) {
        // 1. 获取并校验任务
        DeliveryTask deliveryTask = this.getById(taskId);
        if (deliveryTask == null) {
            throw new BusinessException("未找到该配送任务");
        }
        if (!deliveryTask.getVolunteerId().equals(userId)) {
            throw new BusinessException("权限不足：您不是该任务的执行人");
        }

        // 🚨 校验状态：2:已取货 才能核销
        if (deliveryTask.getTaskStatus() != 2) {
            throw new BusinessException("任务当前状态无法核销（需先确认取货）");
        }

        // 2. 更新任务状态为“已完成(3)”
        deliveryTask.setTaskStatus((byte) 3);
        deliveryTask.setCompleteTime(LocalDateTime.now());
        this.updateById(deliveryTask);

        // 3. 同步更新原始订单表状态为“已送达(2)”
        DispatchOrder dispatchOrder = orderService.getById(deliveryTask.getOrderId());
        if (dispatchOrder != null) {
            dispatchOrder.setStatus((byte) 2);
            orderService.updateById(dispatchOrder);
        }

        // 4. 结算信誉分奖励
        rewardVolunteerCredit(userId, deliveryTask.getOrderId());
    }

    /**
     * 内部方法：处理信用分累加与日志记录
     */
    private void rewardVolunteerCredit(Long userId, Long orderId) {
        int rewardPoints = 5; // 基础奖励分
        User user = userService.getById(userId);

        if (user != null && user.getRole() != null && user.getRole() == 3) {
            int oldScore = user.getCreditScore() != null ? user.getCreditScore() : 0;
            user.setCreditScore(oldScore + rewardPoints);
            userService.updateById(user);

            // 🚨 修正：将变量名改为 creditLog，避免和 @Slf4j 的 log 冲突
            CreditLog creditLog = new CreditLog();
            creditLog.setUserId(userId);
            creditLog.setOrderId(orderId);
            creditLog.setChangeValue(rewardPoints);
            creditLog.setReason("完成订单送达，发放积分奖励");
            creditLog.setCreateTime(LocalDateTime.now());

            creditLogService.save(creditLog);

            // 现在的 log 正确指向了日志打印器
            log.info("志愿者[{}]完成配送，信誉分增加{}，当前总分:{}", userId, rewardPoints, user.getCreditScore());
        }
    }
}