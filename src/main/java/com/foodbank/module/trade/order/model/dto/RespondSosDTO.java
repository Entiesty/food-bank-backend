package com.foodbank.module.trade.order.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RespondSosDTO {

    @NotNull(message = "SOS订单ID不能为空")
    private Long orderId;

    @NotBlank(message = "物资名称不能为空")
    private String goodsName;

    @NotBlank(message = "物资类别不能为空")
    private String category;

    @NotNull(message = "捐赠数量不能为空")
    @Min(value = 1)
    private Integer stock;

    private String unit;

    @NotNull(message = "过期时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expirationDate;

    @NotNull(message = "重量评估不能为空")
    private Integer weightLevel;

    @NotNull(message = "体积评估不能为空")
    private Integer volumeLevel;

    private String goodsImageUrl;

    private BigDecimal estimatedValue;

    private Boolean isSelfDelivery;
}
