package com.foodbank.module.resource.goods.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "商家捐赠物资请求体")
public class DonateDTO {

    @NotBlank(message = "物资名称不能为空")
    @Schema(description = "物资名称")
    private String goodsName;

    @NotBlank(message = "物资类别不能为空")
    @Schema(description = "物资大类（如：米面粮油）")
    private String category;

    @NotNull(message = "捐赠数量不能为空")
    @Min(value = 1, message = "捐赠数量至少为 1")
    @Schema(description = "捐赠数量")
    private Integer stock;

    @NotNull(message = "过期时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "过期/临期时间")
    private LocalDateTime expirationDate;

    @NotNull(message = "接收驿站不能为空")
    @Schema(description = "捐入的社区驿站 ID")
    private Long currentStationId;

    // 🚨 接收商家前端勾选的标签数组
    @Schema(description = "物资特征标签数组")
    private java.util.List<String> tags;

    // 🚨 新增：接收定向捐赠的目标求救单 ID (平时普通捐赠时该字段为 null)
    @Schema(description = "定向响应的紧急求救单ID")
    private Long targetOrderId;
}