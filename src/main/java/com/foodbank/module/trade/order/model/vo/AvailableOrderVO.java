package com.foodbank.module.trade.order.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
@Schema(description = "抢单大厅-待抢订单视图")
public class AvailableOrderVO {
    @Schema(description = "订单ID")
    private Long orderId;
    @Schema(description = "需求类别")
    private String requiredCategory;
    @Schema(description = "紧急度(1-10)")
    private Byte urgencyLevel;

    @Schema(description = "受赠方(终点)经度")
    private java.math.BigDecimal targetLon;
    @Schema(description = "受赠方(终点)纬度")
    private java.math.BigDecimal targetLat;

    @Schema(description = "起点据点ID")
    private Long sourceStationId;
    @Schema(description = "起点据点名称(去哪取货)")
    private String sourceStationName;
    @Schema(description = "起点据点地址")
    private String sourceStationAddress;
    @Schema(description = "起点经度")
    private java.math.BigDecimal sourceLon;
    @Schema(description = "起点纬度")
    private java.math.BigDecimal sourceLat;

    @Schema(description = "发布时间")
    private LocalDateTime createTime;
}