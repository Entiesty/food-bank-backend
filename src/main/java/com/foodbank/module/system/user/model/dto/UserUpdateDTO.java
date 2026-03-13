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

    // 🚨 异构运力载具类型
    @Schema(description = "主力交通工具: 1-步行, 2-单车, 3-电驴, 4-汽车")
    private Integer vehicleType;

    // 🚨🚨 核心修复：这就是你漏掉的罪魁祸首！加上它，报错瞬间消失！
    @Schema(description = "资质证明OSS/MinIO链接")
    private String identityProofUrl;

    // ==========================================
    // 💡 顺手排雷：前端受赠方还传了下面这三个字段，
    // 建议你一并加上，免得以后要在数据库存门牌号时又报错
    // ==========================================
    @Schema(description = "详细门牌号")
    private String doorNumber;

    @Schema(description = "紧急联系人电话")
    private String emergencyPhone;

    @Schema(description = "健康与送达备注")
    private String healthRemark;
}