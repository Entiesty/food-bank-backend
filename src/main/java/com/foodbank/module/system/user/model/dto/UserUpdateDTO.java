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

    @Schema(description = "受赠方关怀标签(逗号分隔多选)")
    private String userTag;

    @Schema(description = "商家行业经营范围")
    private Byte industryType;

    @Schema(description = "受赠方配送方式: 0=可自取食物银行, 1=仅限上门配送")
    private Integer deliveryType;
}