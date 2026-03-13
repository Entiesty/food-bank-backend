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

@Getter
@Setter
@TableName("fb_goods")
@Schema(name = "Goods", description = "物资库存与流转表")
public class Goods implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "goods_id", type = IdType.AUTO)
    private Long goodsId;

    @TableField("merchant_id")
    private Long merchantId;

    @TableField("current_station_id")
    private Long currentStationId;

    @TableField("goods_name")
    private String goodsName;

    @TableField("category")
    private String category;

    @TableField("stock")
    private Integer stock;

    @TableField("status")
    private Byte status;

    @TableField("is_emergency_only")
    private Byte isEmergencyOnly;

    @TableField("allowed_tags")
    private String allowedTags;

    @TableField("expiration_date")
    private LocalDateTime expirationDate;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("tags")
    private String tags;

    // 🚨 本次新增
    @TableField("volume_level")
    private Integer volumeLevel;

    @TableField("weight_level")
    private Integer weightLevel;
}