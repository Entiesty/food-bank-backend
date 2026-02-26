package com.foodbank.module.dispatch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 志愿者配送执行任务表
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Getter
@Setter
@TableName("fb_task")
@Schema(name = "Task", description = "志愿者配送执行任务表")
public class Task implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "task_id", type = IdType.AUTO)
    private Long taskId;

    @Schema(description = "关联订单ID")
    @TableField("order_id")
    private Long orderId;

    @Schema(description = "接单志愿者ID")
    @TableField("volunteer_id")
    private Long volunteerId;

    @Schema(description = "乐观锁版本号(解决高并发抢单)")
    @TableField("version")
    private Integer version;

    @Schema(description = "1:已接单, 2:已取货, 3:已完成")
    @TableField("task_status")
    private Byte taskStatus;

    @Schema(description = "抢单时间")
    @TableField("accept_time")
    private LocalDateTime acceptTime;

    @Schema(description = "送达核销时间")
    @TableField("complete_time")
    private LocalDateTime completeTime;
}
