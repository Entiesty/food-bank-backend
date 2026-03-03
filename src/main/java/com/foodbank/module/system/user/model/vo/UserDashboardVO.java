package com.foodbank.module.system.user.model.vo;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class UserDashboardVO {
    // 基础通用信息
    private String avatar;
    private Byte role;
    private String username;

    // ================= 🚴 志愿者专属数据 =================
    private Integer creditScore;          // 当前信誉分
    private Integer totalDeliveredOrders; // 累计完成派送单数
    private BigDecimal runningMileage;    // 累计有氧慢跑里程 (公里，基于 LBS 距离换算)

    // ================= 🏪 爱心商家专属数据 =================
    private Integer totalDonatedGoods;    // 累计捐赠物资数量 (件/份)
    private Integer totalHelpCount;       // 累计帮助人次 (关联的已送达订单数)

    // ================= 👴 受赠方专属数据 =================
    private String userTag;               // 特殊身份标签 (如 ELDERLY)
    private Integer totalReceivedTimes;   // 累计获得援助次数
}