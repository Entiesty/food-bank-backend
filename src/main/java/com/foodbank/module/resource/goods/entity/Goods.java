package com.foodbank.module.resource.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 物资库存与流转表
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Getter
@Setter
@TableName("fb_goods")
@Schema(name = "Goods", description = "物资库存与流转表")
public class Goods implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "goods_id", type = IdType.AUTO)
    private Long goodsId;

    @Schema(description = "提供物资的商家ID")
    @TableField("merchant_id")
    private Long merchantId;

    @Schema(description = "当前所在据点ID(为空则仍在商家手中)")
    @TableField("current_station_id")
    private Long currentStationId;

    @Schema(description = "物资名称")
    @TableField("goods_name")
    private String goodsName;

    @Schema(description = "类别: 临期食品, 医疗包, 饮用水")
    @TableField("category")
    private String category;

    @Schema(description = "库存数量")
    @TableField("stock")
    private Integer stock;

    @Schema(description = "状态: 0-待取货(商家处), 1-运输中, 2-已入库(据点), 3-已发完")
    @TableField("status")
    private Byte status;

    @Schema(description = "1:仅应急模式可见")
    @TableField("is_emergency_only")
    private Byte isEmergencyOnly;

    @Schema(description = "限定领取群体(ALL或逗号分隔的标签)")
    @TableField("allowed_tags")
    private String allowedTags;

    @Schema(description = "临期/过期时间")
    @TableField("expiration_date")
    private LocalDateTime expirationDate;

    @TableField("create_time")
    private LocalDateTime createTime;
}
