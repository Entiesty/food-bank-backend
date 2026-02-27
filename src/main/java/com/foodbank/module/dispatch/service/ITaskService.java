package com.foodbank.module.dispatch.service;

import com.foodbank.module.dispatch.entity.Task;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 志愿者配送执行任务表 服务类
 * </p>
 */
public interface ITaskService extends IService<Task> {

    /**
     * 完成任务核销并结算奖励
     * @param taskId 任务ID (已修正为 Long)
     * @param userId 志愿者ID
     */
    void completeTask(Long taskId, Long userId);
}