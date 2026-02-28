package com.foodbank.module.trade.task.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.common.api.Result;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.trade.task.model.vo.MyTaskVO;
import com.foodbank.module.trade.task.service.IDeliveryTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Task Controller", description = "志愿者任务执行与核销管理")
@RestController
@RequestMapping("/trade/task") // 🚨 修复路径前缀
public class DeliveryTaskController {

    @Autowired
    private IDeliveryTaskService taskService;

    @Operation(summary = "3. 确认送达核销任务", description = "志愿者到达目的地后核销，系统自动结算信誉分奖励")
    @PostMapping("/checkout")
    public Result<String> checkOutTask(
            @Parameter(description = "任务ID", required = true) @RequestParam Long taskId) {
        Long myVolunteerId = UserContext.getUserId();
        taskService.completeTask(taskId, myVolunteerId);
        return Result.success("核销成功！信誉分已奖励，感谢您的付出。");
    }

    @Operation(summary = "4. 获取我的任务列表", description = "志愿者获取自己当前的历史和执行中的任务")
    @GetMapping("/my-tasks")
    public Result<Page<MyTaskVO>> getMyTasks(
            @Parameter(description = "任务状态筛选项(1接单 2取货 3完成, 不传则查全部)") @RequestParam(required = false) Byte status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        Long myVolunteerId = UserContext.getUserId();
        return Result.success(taskService.getMyTasksPage(myVolunteerId, status, pageNum, pageSize));
    }
}