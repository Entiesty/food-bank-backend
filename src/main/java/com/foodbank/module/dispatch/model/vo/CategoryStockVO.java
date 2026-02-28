package com.foodbank.module.dispatch.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "物资分类库存统计视图")
public class CategoryStockVO {
    @Schema(description = "物资类别名 (如：医疗包)")
    private String categoryName;

    @Schema(description = "该类别总库存")
    private Integer totalStock;
}