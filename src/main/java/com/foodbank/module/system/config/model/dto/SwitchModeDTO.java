package com.foodbank.module.system.config.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SwitchModeDTO {

    @NotBlank(message = "目标模式不能为空")
    private String targetMode;
}
