package com.foodbank.module.resource.station.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "驿站真实距离视图对象")
public class StationRecommendVO {
    private Long stationId;
    private String stationName;
    private String address;

    @Schema(description = "用于排序的原始距离")
    private Double rawDistance;

    @Schema(description = "格式化后的距离 (如 1.25km)")
    private String distance;

    // 👇👇👇 🚨 核心补漏：把这两个关键标识透传给前端
    @Schema(description = "是否有冷柜/冷链储藏能力: 0-无, 1-有")
    private Integer hasFreezer;

    @Schema(description = "1:应急核心调度站(急时优先补给), 0:常态化")
    private Integer isEmergencyHub;
}