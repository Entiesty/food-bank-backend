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

    @Schema(description = "常驻基站经度")
    private Double currentLon;

    @Schema(description = "常驻基站纬度")
    private Double currentLat;

    @Schema(description = "资质证明图片链接")
    private String identityProofUrl;

    @Schema(description = "骑士载具类型")
    private Integer vehicleType;

    @Schema(description = "受赠方门牌号")
    private String doorNumber;

    @Schema(description = "受赠方紧急电话")
    private String emergencyPhone;

    @Schema(description = "受赠方健康备注")
    private String healthRemark;
}