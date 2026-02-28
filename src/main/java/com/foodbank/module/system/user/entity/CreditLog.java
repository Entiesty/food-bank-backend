package com.foodbank.module.system.user.entity;

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
 * 信誉分变动明细记录表
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Getter
@Setter
@TableName("fb_credit_log")
@Schema(name = "CreditLog", description = "信誉分变动明细记录表")
public class CreditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "log_id", type = IdType.AUTO)
    private Long logId;

    @Schema(description = "志愿者ID")
    @TableField("user_id")
    private Long userId;

    @Schema(description = "关联订单(可选)")
    @TableField("order_id")
    private Long orderId;

    @Schema(description = "变动分值(正负)")
    @TableField("change_value")
    private Integer changeValue;

    @Schema(description = "变动原因")
    @TableField("reason")
    private String reason;

    @TableField("create_time")
    private LocalDateTime createTime;
}
