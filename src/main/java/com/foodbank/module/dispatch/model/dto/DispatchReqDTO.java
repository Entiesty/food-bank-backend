package com.foodbank.module.dispatch.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 智能派单请求参数 DTO
 */
@Data
@Schema(description = "智能派单请求参数")
public class DispatchReqDTO {

    @Schema(description = "受赠方实时经度", requiredMode = Schema.RequiredMode.REQUIRED, example = "118.089425")
    @NotNull(message = "经度不能为空")
    private Double longitude;

    @Schema(description = "受赠方实时纬度", requiredMode = Schema.RequiredMode.REQUIRED, example = "24.479833")
    @NotNull(message = "纬度不能为空")
    private Double latitude;

    @Schema(description = "需求物资ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "物资ID不能为空")
    private Long goodsId;

    @Schema(description = "订单紧急度 (1-10)", example = "8")
    @Min(value = 1, message = "紧急度最低为1")
    @Max(value = 10, message = "紧急度最高为10")
    private Integer urgency = 5; // 默认中等紧急
}