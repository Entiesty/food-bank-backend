package com.foodbank.module.trade.task.service;

import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 志愿者配送执行任务表 服务类
 * </p>
 */
public interface IDeliveryTaskService extends IService<DeliveryTask> {

    /**
     * 完成任务核销并结算奖励
     * @param taskId 任务ID (已修正为 Long)
     * @param userId 志愿者ID
     */
    void completeTask(Long taskId, Long userId);
}