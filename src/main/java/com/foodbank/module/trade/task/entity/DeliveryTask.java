package com.foodbank.module.trade.task.entity;

import com.baomidou.mybatisplus.annotation.*;

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
public class DeliveryTask implements Serializable {

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
    @Version
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

    // 🚀 核心新增：核销凭证照片URL，必须与数据库字段名对应！
    @Schema(description = "核销凭证照片URL")
    @TableField("proof_image")
    private String proofImage;
}