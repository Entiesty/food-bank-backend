package com.foodbank.module.trade.order.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@Schema(description = "抢单大厅-待抢订单视图")
public class AvailableOrderVO {
    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "订单号")
    private String orderSn;
    @Schema(description = "具体物资名称")
    private String goodsName;
    @Schema(description = "物资件数")
    private Integer goodsCount;

    @Schema(description = "需求类别")
    private String requiredCategory;
    @Schema(description = "紧急度(1-10)")
    private Byte urgencyLevel;

    // ====== 统一抽象的起点信息 (取货地) ======
    @Schema(description = "起点名称")
    private String sourceName;
    @Schema(description = "起点地址/联系方式")
    private String sourceAddress;
    @Schema(description = "起点经度")
    private BigDecimal sourceLon;
    @Schema(description = "起点纬度")
    private BigDecimal sourceLat;

    // ====== 统一抽象的终点信息 (送达地) ======
    @Schema(description = "终点名称")
    private String targetName;
    @Schema(description = "终点地址/联系方式")
    private String targetAddress;
    @Schema(description = "终点经度")
    private BigDecimal targetLon;
    @Schema(description = "终点纬度")
    private BigDecimal targetLat;

    @Schema(description = "发布时间")
    private LocalDateTime createTime;
}