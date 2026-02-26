package com.foodbank.module.dispatch.service.impl;

import com.foodbank.module.dispatch.entity.Task;
import com.foodbank.module.dispatch.mapper.TaskMapper;
import com.foodbank.module.dispatch.service.ITaskService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 志愿者配送执行任务表 服务实现类
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Service
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements ITaskService {

}
