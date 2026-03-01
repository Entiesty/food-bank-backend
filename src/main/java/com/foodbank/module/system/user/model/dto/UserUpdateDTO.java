package com.foodbank.module.system.user.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "用户资料更新请求体")
public class UserUpdateDTO {
    @Schema(description = "真实姓名/昵称")
    @NotBlank(message = "姓名不能为空")
    private String username;
}