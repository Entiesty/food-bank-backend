package com.foodbank.module.dispatch.strategy;

import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import com.foodbank.module.system.config.entity.Config;
import com.foodbank.module.system.config.service.IConfigService;
import com.foodbank.module.trade.order.model.vo.AvailableOrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * 核心：多因子加权调度决策算法 (动态配置热加载增强版)
 */
@Slf4j
@Component
public class MultiFactorDispatchStrategy {

    @Autowired
    private IConfigService configService;

    /**
     * 对候选物资/据点进行综合打分并排序 (系统大盘自动指派使用)
     */
    public List<DispatchCandidateVO> calculateAndRank(List<DispatchCandidateVO> candidates, int orderUrgency) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        Config activeConfig = configService.getCurrentConfig();
        double wDist = activeConfig.getWDist().doubleValue();
        double wUrgency = activeConfig.getWUrgency().doubleValue();
        double wStock = 0.10;
        double wExpiration = 0.10;

        long maxDistance = candidates.stream().mapToLong(DispatchCandidateVO::getDistance).max().orElse(1L);
        long minDistance = candidates.stream().mapToLong(DispatchCandidateVO::getDistance).min().orElse(0L);
        long distanceRange = Math.max(maxDistance - minDistance, 1L);

        int maxStock = candidates.stream().mapToInt(DispatchCandidateVO::getCurrentStock).max().orElse(1);
        int minStock = candidates.stream().mapToInt(DispatchCandidateVO::getCurrentStock).min().orElse(0);
        int stockRange = Math.max(maxStock - minStock, 1);

        long maxExpTime = candidates.stream().mapToLong(c -> c.getGoods().getExpirationDate().toEpochSecond(ZoneOffset.UTC)).max().orElse(1L);
        long minExpTime = candidates.stream().mapToLong(c -> c.getGoods().getExpirationDate().toEpochSecond(ZoneOffset.UTC)).min().orElse(0L);
        long expRange = Math.max(maxExpTime - minExpTime, 1L);

        double normUrgency = orderUrgency / 10.0;

        for (DispatchCandidateVO candidate : candidates) {
            double normDistance = (double) (maxDistance - candidate.getDistance()) / distanceRange;
            double normStock = (double) (candidate.getCurrentStock() - minStock) / stockRange;

            long currentExpTime = candidate.getGoods().getExpirationDate().toEpochSecond(ZoneOffset.UTC);
            double normExpiration = (double) (maxExpTime - currentExpTime) / expRange;

            double emergencyBonus = (orderUrgency >= 8 && candidate.getStation().getIsEmergencyHub() == 1) ? 1.0 : 0.0;

            double finalScore = (normDistance * wDist)
                    + (normStock * wStock)
                    + (normExpiration * wExpiration)
                    + ((normUrgency + emergencyBonus) * wUrgency);

            candidate.setFinalScore(finalScore);
        }

        candidates.sort(Comparator.comparing(DispatchCandidateVO::getFinalScore).reversed());
        return candidates;
    }

    /**
     * 🚀 核心新增：为志愿者抢单大厅提供千人千面的排序测算
     * 计算真正的“接驾距离”，并融合信誉与紧急度进行最终打分
     */
    public void rankOrdersForVolunteer(List<AvailableOrderVO> orders, Double volLon, Double volLat, int volCredit) {
        if (orders == null || orders.isEmpty() || volLon == null || volLat == null) {
            return;
        }

        Config activeConfig = configService.getCurrentConfig();
        double wDist = activeConfig.getWDist().doubleValue();
        double wUrgency = activeConfig.getWUrgency().doubleValue();
        double wCredit = activeConfig.getWCredit().doubleValue();

        // 1. 计算接驾距离并寻找极值用于归一化
        double maxDist = 1.0;
        double minDist = Double.MAX_VALUE;

        for (AvailableOrderVO order : orders) {
            double dist = 999.0;
            // 只有当订单起点坐标存在时，才计算志愿者当前坐标与起点的距离
            if (order.getSourceLon() != null && order.getSourceLat() != null) {
                dist = calculateDistance(volLat, volLon, order.getSourceLat().doubleValue(), order.getSourceLon().doubleValue());
            }
            order.setPickupDistance(dist);

            if (dist > maxDist && dist != 999.0) maxDist = dist;
            if (dist < minDist) minDist = dist;
        }

        double distRange = Math.max(maxDist - minDist, 1.0);

        // 2. 多因子加权打分
        for (AvailableOrderVO order : orders) {
            // A. 距离反向归一化 (越近接驾分越高)
            double normDist = (order.getPickupDistance() == 999.0) ? 0 : ((maxDist - order.getPickupDistance()) / distRange);
            // B. 紧急度正向归一化 (越急分越高)
            double normUrgency = order.getUrgencyLevel() / 10.0;
            // C. 信誉分赋能 (高信誉骑士获得基础分加成，更容易抢到好单)
            double normCredit = Math.min(volCredit / 150.0, 1.0);

            double finalScore = (normDist * wDist) + (normUrgency * wUrgency) + (normCredit * wCredit);
            order.setMatchScore(finalScore);
        }

        // 3. 按照得分从高到低排序
        orders.sort(Comparator.comparing(AvailableOrderVO::getMatchScore).reversed());
    }

    /**
     * 📐 Haversine 直线距离测算 (km)
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}