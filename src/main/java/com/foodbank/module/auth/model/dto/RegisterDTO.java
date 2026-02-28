package com.foodbank.module.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "多角色注册表单传输对象")
public class RegisterDTO {

    @Schema(description = "手机号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @Schema(description = "真实姓名/商家名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @Schema(description = "注册角色：1-受赠方, 2-爱心商家, 3-志愿者", requiredMode = Schema.RequiredMode.REQUIRED)
    private Byte role;

    @Schema(description = "短信验证码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String smsCode;
}