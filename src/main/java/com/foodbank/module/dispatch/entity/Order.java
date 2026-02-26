package com.foodbank.module.dispatch.entity;

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

/**
 * <p>
 * 双向物流调度订单表
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Getter
@Setter
@TableName("fb_order")
@Schema(name = "Order", description = "双向物流调度订单表")
public class Order implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "order_id", type = IdType.AUTO)
    private Long orderId;

    @Schema(description = "唯一订单编号")
    @TableField("order_sn")
    private String orderSn;

    @Schema(description = "1:供应单(取货: 商家->据点), 2:需求单(送货: 据点->受赠方)")
    @TableField("order_type")
    private Byte orderType;

    @TableField("goods_id")
    private Long goodsId;

    @Schema(description = "起点ID(商家ID或据点ID)")
    @TableField("source_id")
    private Long sourceId;

    @Schema(description = "终点ID(据点ID或受赠方ID)")
    @TableField("dest_id")
    private Long destId;

    @Schema(description = "1:志愿者配送, 2:到店自提")
    @TableField("delivery_method")
    private Byte deliveryMethod;

    @Schema(description = "紧急度(1-10级, 算法权重核心)")
    @TableField("urgency_level")
    private Byte urgencyLevel;

    @Schema(description = "目的地经度")
    @TableField("target_lon")
    private BigDecimal targetLon;

    @Schema(description = "目的地纬度")
    @TableField("target_lat")
    private BigDecimal targetLat;

    @Schema(description = "0-待匹配, 1-调度中, 2-已送达, 3-已取消")
    @TableField("status")
    private Byte status;

    @TableField("create_time")
    private LocalDateTime createTime;
}
