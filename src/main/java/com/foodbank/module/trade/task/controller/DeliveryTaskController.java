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
@RequestMapping("/trade/task")
public class DeliveryTaskController {

    @Autowired
    private IDeliveryTaskService taskService;

    // 🚨 新增：骑士确认取货接口
    @Operation(summary = "5. 确认取货任务", description = "志愿者到达物理据点后确认提取物资")
    @PostMapping("/pickup/{taskId}")
    public Result<String> confirmPickup(@PathVariable Long taskId) {
        taskService.confirmPickup(taskId);
        return Result.success("取货确认成功");
    }

    @Operation(summary = "3. 确认送达核销任务", description = "志愿者到达目的地后核销，并上传现场照片")
    @PostMapping("/complete")
    public Result<String> checkOutTask(
            @Parameter(description = "任务ID", required = true) @RequestParam Long taskId,
            @Parameter(description = "核销凭证照片URL") @RequestParam(required = false) String proofImage) {

        Long myVolunteerId = UserContext.getUserId();
        taskService.completeTask(taskId, myVolunteerId, proofImage);
        return Result.success("核销成功！现场照片已归档，信誉分已奖励。");
    }

    @Operation(summary = "4. 获取我的任务列表", description = "志愿者获取自己当前的历史和执行中的任务")
    @GetMapping("/my-list")
    public Result<Page<MyTaskVO>> getMyTasks(
            @Parameter(description = "任务状态筛选项(1接单 2取货 3完成, 不传则查全部)") @RequestParam(required = false) Byte status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        Long myVolunteerId = UserContext.getUserId();
        return Result.success(taskService.getMyTasksPage(myVolunteerId, status, pageNum, pageSize));
    }
}