package com.foodbank.module.resource.station.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 物资物理据点实体类
 */
@Data
@TableName("fb_station")
public class Station {

    @TableId(type = IdType.AUTO)
    private Long stationId;

    /**
     * 据点名称(如: XX社区爱心驿站)
     */
    private String stationName;

    /**
     * 详细地址
     */
    private String address;

    /**
     * 固定经纬度 (使用 BigDecimal 防止 LBS 计算精度丢失)
     */
    private BigDecimal longitude;
    private BigDecimal latitude;

    /**
     * 负责人ID(关联sys_user)
     */
    private Long managerId;

    /**
     * 1:应急核心调度站(急时优先补给), 0:常态化
     */
    private Integer isEmergencyHub;

    private LocalDateTime createTime;
}