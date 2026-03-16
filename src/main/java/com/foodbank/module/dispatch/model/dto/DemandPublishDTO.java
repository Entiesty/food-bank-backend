package com.foodbank.module.dispatch.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

@Data
public class DemandPublishDTO {

    @NotBlank(message = "需要的物资类别不能为空")
    private String requiredCategory;

    @NotNull(message = "紧急程度不能为空")
    private Integer urgencyLevel;

    @NotNull(message = "目标经度不能为空")
    private BigDecimal targetLon;

    @NotNull(message = "目标纬度不能为空")
    private BigDecimal targetLat;

    private String description;

    @Schema(description = "根据老人行为隐式推导出的需求标签数组")
    private List<String> requiredTags;

    @NotNull(message = "请选择取货方式")
    private Integer deliveryMethod;

    // 🚨 本次新增：为了实现精准的【自提锁库存沙箱】，必须加上以下两个字段
    @Schema(description = "明确预约的物资ID(自提用)")
    private Long goodsId;

    @Schema(description = "明确预约的物理据点ID(自提用)")
    private Long sourceId;
}