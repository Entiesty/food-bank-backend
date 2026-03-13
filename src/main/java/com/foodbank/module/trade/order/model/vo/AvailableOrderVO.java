package com.foodbank.module.trade.order.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailableOrderVO {

    private Long orderId;
    private String orderSn;

    private String goodsName;
    private Integer goodsCount;
    private String requiredCategory;
    private Byte urgencyLevel;

    private String sourceName;
    private String sourceAddress;
    private BigDecimal sourceLon;
    private BigDecimal sourceLat;

    private String targetName;
    private String targetAddress;
    private BigDecimal targetLon;
    private BigDecimal targetLat;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    // ==========================================
    // 🚀 千人千面动态算法字段 (不存数据库，实时计算)
    // ==========================================

    /**
     * 志愿者距离起点的接驾距离 (km)
     */
    private Double pickupDistance;

    /**
     * 多因子调度引擎算出的综合匹配分
     */
    private Double matchScore;
}