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

    private LocalDateTime updateTime;
}