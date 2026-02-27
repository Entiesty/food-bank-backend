package com.foodbank.module.dispatch.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.dispatch.entity.CreditLog;
import com.foodbank.module.dispatch.entity.Order;
import com.foodbank.module.dispatch.entity.Task;
import com.foodbank.module.dispatch.mapper.TaskMapper;
import com.foodbank.module.dispatch.service.ICreditLogService;
import com.foodbank.module.dispatch.service.IOrderService;
import com.foodbank.module.dispatch.service.ITaskService;
import com.foodbank.module.system.entity.User;
import com.foodbank.module.system.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements ITaskService {

    @Autowired
    private IOrderService orderService;
    @Autowired
    private IUserService userService;
    @Autowired
    private ICreditLogService creditLogService;

    @Override
    @Transactional(rollbackFor = Exception.class) // 🚨 保证原子性
    public void completeTask(Long taskId, Long userId) {
        // 1. 获取并校验任务
        Task task = this.getById(taskId);
        if (task == null) {
            throw new BusinessException("未找到该配送任务");
        }
        if (!task.getVolunteerId().equals(userId)) {
            throw new BusinessException("权限不足：您不是该任务的执行人");
        }

        // 🚨 校验状态：2:已取货 才能核销
        if (task.getTaskStatus() != 2) {
            throw new BusinessException("任务当前状态无法核销（需先确认取货）");
        }

        // 2. 更新任务状态为“已完成(3)”
        task.setTaskStatus((byte) 3);
        task.setCompleteTime(LocalDateTime.now());
        this.updateById(task);

        // 3. 同步更新原始订单表状态为“已送达(2)”
        Order order = orderService.getById(task.getOrderId());
        if (order != null) {
            order.setStatus((byte) 2);
            orderService.updateById(order);
        }

        // 4. 结算信誉分奖励
        rewardVolunteerCredit(userId, task.getOrderId());
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