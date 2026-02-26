package com.foodbank.module.system.entity;

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
 * 系统动态权重配置表
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Getter
@Setter
@TableName("sys_config")
@Schema(name = "Config", description = "系统动态权重配置表")
public class Config implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "模式: NORMAL-平时, EMERGENCY-急时")
    @TableField("sys_mode")
    private String sysMode;

    @Schema(description = "距离权重")
    @TableField("w_dist")
    private BigDecimal wDist;

    @Schema(description = "紧急度权重")
    @TableField("w_urgency")
    private BigDecimal wUrgency;

    @Schema(description = "信誉分权重")
    @TableField("w_credit")
    private BigDecimal wCredit;

    @Schema(description = "身份标签权重(如老人优先)")
    @TableField("w_tag")
    private BigDecimal wTag;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
