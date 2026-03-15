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

    @Schema(description = "资质证明图片URL")
    private String identityProofUrl;

    // 👇新增：接收商家注册时的行业选择
    @Schema(description = "行业经营范围(仅商家注册需传): 1-餐饮生鲜, 2-商超便利, 3-医药器械, 4-服饰百货")
    private Byte industryType;

    // 👇新增：接收受赠方注册时的身份标签
    @Schema(description = "受赠方身份标签(NORMAL/ELDERLY/DISABLED/SAN_WORKER)")
    private String userTag;
}