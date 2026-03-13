package com.foodbank.module.system.user.entity;

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

@Getter
@Setter
@TableName("sys_user")
@Schema(name = "User", description = "用户信息与信用体系表")
public class User implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    @TableField("username")
    private String username;

    @TableField("password")
    private String password;

    @TableField("role")
    private Byte role;

    @TableField("user_tag")
    private String userTag;

    @TableField("is_verified")
    private Byte isVerified;

    @TableField("identity_proof_url")
    private String identityProofUrl;

    @TableField("phone")
    private String phone;

    @TableField("credit_score")
    private Integer creditScore;

    @TableField("current_lon")
    private BigDecimal currentLon;

    @TableField("current_lat")
    private BigDecimal currentLat;

    @TableField("status")
    private Byte status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("avatar")
    private String avatar;

    @TableField("industry_type")
    private Byte industryType;

    // 🚨 本次新增：异构运力标识
    @TableField("vehicle_type")
    private Integer vehicleType;
}