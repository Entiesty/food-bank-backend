package com.foodbank.module.dispatch.controller;

import com.foodbank.common.api.Result;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.dispatch.service.ITaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Task Controller", description = "志愿者任务执行与核销管理")
@RestController
@RequestMapping("/dispatch/task")
public class TaskController {

    @Autowired
    private ITaskService taskService;

    @Operation(summary = "3. 确认送达核销任务", description = "志愿者到达目的地后核销，系统自动结算信誉分奖励")
    @PostMapping("/checkout")
    public Result<String> checkOutTask(
            @Parameter(description = "任务ID", required = true) @RequestParam Long taskId) {

        // 🚨 安全增强：从线程上下文中获取真实的志愿者ID
        Long myVolunteerId = UserContext.getUserId();

        // 调用 Service 层处理状态变更、信誉分累加及信用日志记录的事务逻辑
        taskService.completeTask(taskId, myVolunteerId);

        return Result.success("核销成功！信誉分已奖励，感谢您的付出。");
    }
}