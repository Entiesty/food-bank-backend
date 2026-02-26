package com.foodbank.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 用户信息与信用体系表
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Getter
@Setter
@TableName("sys_user")
@Schema(name = "User", description = "用户信息与信用体系表")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    @Schema(description = "登录账号")
    @TableField("username")
    private String username;

    @Schema(description = "加密后的密码")
    @TableField("password")
    private String password;

    @Schema(description = "1:受赠方(如环卫工), 2:供应商家, 3:志愿者, 4:管理员")
    @TableField("role")
    private Byte role;

    @Schema(description = "身份标签(逗号分隔): SAN_WORKER, DISABLED, ELDERLY, LOW_INCOME")
    @TableField("user_tag")
    private String userTag;

    @Schema(description = "标签是否核实: 0-未核实, 1-已核实")
    @TableField("is_verified")
    private Byte isVerified;

    @Schema(description = "证明材料图片路径")
    @TableField("identity_proof_url")
    private String identityProofUrl;

    @Schema(description = "手机号(唯一索引)")
    @TableField("phone")
    private String phone;

    @Schema(description = "志愿者信誉分(用于权限控制与算法加权)")
    @TableField("credit_score")
    private Integer creditScore;

    @Schema(description = "实时经度(由移动端上报)")
    @TableField("current_lon")
    private BigDecimal currentLon;

    @Schema(description = "实时纬度")
    @TableField("current_lat")
    private BigDecimal currentLat;

    @Schema(description = "1:正常, 0:禁用")
    @TableField("status")
    private Byte status;

    @TableField("create_time")
    private LocalDateTime createTime;
}
