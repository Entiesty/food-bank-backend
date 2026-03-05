package com.foodbank.module.resource.goods.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Schema(description = "商家捐赠记录视图对象")
public class MerchantGoodsVO {
    private Long goodsId;
    private String goodsName;
    private String category;
    private Integer stock;

    @Schema(description = "0-待取货, 1-干线运输中, 2-已入库, 3-已发完")
    private Byte status;

    private LocalDateTime expirationDate;
    private LocalDateTime createTime;

    @Schema(description = "关联查询出的目标驿站名称")
    private String stationName;

    private Double stationLon;
    private Double stationLat;
}