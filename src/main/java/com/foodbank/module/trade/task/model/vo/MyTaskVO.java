package com.foodbank.module.trade.task.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
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
    @Schema(description = "订单编号")
    private String orderSn;

    @Schema(description = "具体物资名称")
    private String goodsName;
    @Schema(description = "物资件数")
    private Integer goodsCount;
    @Schema(description = "紧急度(1-10)")
    private Byte urgencyLevel;
    @Schema(description = "配送物资大类")
    private String requiredCategory;

    // ====== 统一抽象的起点信息 ======
    @Schema(description = "起点名称")
    private String sourceName;
    @Schema(description = "起点地址")
    private String sourceAddress;
    @Schema(description = "起点经度")
    private BigDecimal sourceLon;
    @Schema(description = "起点纬度")
    private BigDecimal sourceLat;

    // ====== 统一抽象的终点信息 ======
    @Schema(description = "终点名称")
    private String targetName;
    @Schema(description = "终点地址")
    private String targetAddress;
    @Schema(description = "终点经度")
    private BigDecimal targetLon;
    @Schema(description = "终点纬度")
    private BigDecimal targetLat;
}