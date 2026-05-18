package com.foodbank.module.system.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
// 👇 1. 引入 JsonProperty 注解
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sys_config")
@Schema(description = "系统动态权重与模式配置表")
public class Config {

    @TableId(type = IdType.AUTO)
    private Integer id;

    @Schema(description = "大盘模式: NORMAL, EMERGENCY")
    private String sysMode;

    // 👇 2. 强制指定返回给前端的 JSON 字段名（保持驼峰）
    @JsonProperty("wDist")
    @Schema(description = "多因子权重-距离偏好")
    private BigDecimal wDist;

    @JsonProperty("wUrgency")
    @Schema(description = "多因子权重-紧急度偏好")
    private BigDecimal wUrgency;

    @JsonProperty("wCredit")
    @Schema(description = "多因子权重-骑手信誉偏好")
    private BigDecimal wCredit;

    @JsonProperty("wTag")
    @Schema(description = "多因子权重-弱势群体身份偏好")
    private BigDecimal wTag;

    @JsonProperty("wExpiration")
    @Schema(description = "多因子权重-物资临期偏好(FEFO)")
    private BigDecimal wExpiration;

    @JsonProperty("wStock")
    @Schema(description = "多因子权重-据点库存偏好")
    private BigDecimal wStock;

    @JsonProperty("wTimeCoin")
    @Schema(description = "多因子权重-志愿者时间币偏好")
    private BigDecimal wTimeCoin;

    private LocalDateTime updateTime;

    @Schema(description = "当前模式激活时间")
    private LocalDateTime modeActivatedAt;

    @Schema(description = "模式切换操作人ID")
    private Long modeActivatedBy;

    @Schema(description = "灾区每人每日最大申领次数")
    private Integer maxDailyClaims;

    @Schema(description = "每公里补贴单价(元)")
    private BigDecimal subsidyPerKm;
}