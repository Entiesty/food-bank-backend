package com.foodbank.module.trade.task.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
@Schema(description = "志愿者-我的任务视图")
public class MyTaskVO {
    @Schema(description = "任务ID")
    private Long taskId;
    @Schema(description = "任务状态: 1-已接单(待取货), 2-已取货(配送中), 3-已完成")
    private Byte taskStatus;
    @Schema(description = "接单时间")
    private LocalDateTime acceptTime;

    @Schema(description = "关联订单ID")
    private Long orderId;
    @Schema(description = "配送物资大类")
    private String requiredCategory;

    @Schema(description = "取货地名称")
    private String stationName;
    @Schema(description = "取货地地址")
    private String stationAddress;

    @Schema(description = "送达目的地经度")
    private java.math.BigDecimal targetLon;
    @Schema(description = "送达目的地纬度")
    private java.math.BigDecimal targetLat;
}