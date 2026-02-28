package com.foodbank.module.trade.task.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.trade.task.model.vo.MyTaskVO;

public interface IDeliveryTaskService extends IService<DeliveryTask> {

    void completeTask(Long taskId, Long userId);

    /**
     * 🚀 新增：获取志愿者的任务列表（可按状态过滤）
     */
    Page<MyTaskVO> getMyTasksPage(Long volunteerId, Byte status, int pageNum, int pageSize);
}