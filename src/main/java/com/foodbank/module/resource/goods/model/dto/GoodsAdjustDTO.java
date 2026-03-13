package com.foodbank.module.resource.goods.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "大仓库存人工校准请求参数")
public class GoodsAdjustDTO {

    @Schema(description = "物资ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long goodsId;

    @Schema(description = "干预类型：1-增加(手工入库) 2-减少(损耗/发放)", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer adjustType;

    @Schema(description = "干预变动数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer diffCount;

    @Schema(description = "操作事由(备查)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;
}