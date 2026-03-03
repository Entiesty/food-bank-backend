package com.foodbank.module.system.config.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty; // 👇 引入注解
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ConfigUpdateDTO {

    @NotBlank(message = "系统模式不能为空")
    private String sysMode;

    @NotNull(message = "距离权重不能为空")
    @JsonProperty("wDist") // 👇 强制映射
    private BigDecimal wDist;

    @NotNull(message = "紧急度权重不能为空")
    @JsonProperty("wUrgency")
    private BigDecimal wUrgency;

    @NotNull(message = "信誉权重不能为空")
    @JsonProperty("wCredit")
    private BigDecimal wCredit;

    @NotNull(message = "标签权重不能为空")
    @JsonProperty("wTag")
    private BigDecimal wTag;
}