package com.foodbank.module.trade.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("fb_order")
@Schema(name = "Order", description = "双向物流调度订单表")
public class DispatchOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "order_id", type = IdType.AUTO)
    private Long orderId;

    @TableField("order_sn")
    private String orderSn;

    @TableField("order_type")
    private Byte orderType;

    @TableField("required_category")
    private String requiredCategory;

    @TableField("goods_id")
    private Long goodsId;

    @TableField("source_id")
    private Long sourceId;

    @TableField("dest_id")
    private Long destId;

    @TableField("delivery_method")
    private Byte deliveryMethod;

    @TableField("urgency_level")
    private Byte urgencyLevel;

    @TableField("target_lon")
    private BigDecimal targetLon;

    @TableField("target_lat")
    private BigDecimal targetLat;

    @TableField("status")
    private Byte status;

    @TableField("create_time")
    private LocalDateTime createTime;

    // 🚨 终极闭环：物资明细与数量
    @Schema(description = "具体物资名称 (如: 康师傅矿泉水)")
    @TableField("goods_name")
    private String goodsName;

    @Schema(description = "物资数量/件数")
    @TableField("goods_count")
    private Integer goodsCount;

    @TableField(exist = false)
    private String sourceName;
    @TableField(exist = false)
    private String targetName;

    // 👇 追加这两个字段，用来接收真实的起点经纬度
    @TableField(exist = false)
    private BigDecimal sourceLon;
    @TableField(exist = false)
    private BigDecimal sourceLat;

    // 🚨 新增：用来接收调度引擎写下的“死亡笔记（异常原因）”
    @Schema(description = "调度异常原因/滞留标签")
    @TableField("exception_reason")
    private String exceptionReason;

    @Schema(description = "受赠方评分(1-5星)")
    @TableField("recipient_rating")
    private Integer recipientRating;

    @Schema(description = "受赠方补充评价")
    @TableField("recipient_comment")
    private String recipientComment;

    // 🚨 终极闭环：隐式解析出的需求标签
    @Schema(description = "需求特征标签(隐式解析)")
    @TableField("required_tags")
    private String requiredTags;
}