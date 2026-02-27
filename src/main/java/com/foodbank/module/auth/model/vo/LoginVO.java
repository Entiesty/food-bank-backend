package com.foodbank.module.auth.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录成功后返回给前端的视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录成功响应数据")
public class LoginVO {

    @Schema(description = "访问令牌 JWT")
    private String token;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户姓名/昵称")
    private String username;

    @Schema(description = "用户角色 (1:受赠方, 2:商家, 3:志愿者, 4:管理员)")
    private Byte role;
}