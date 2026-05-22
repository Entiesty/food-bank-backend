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
 * SAW (Simple Additive Weighting) 多因子调度决策策略。
 *
 * 从 Config 实体读取运行时六维权重矩阵 (wDist / wUrgency / wCredit / wTag / wExpiration / wStock)，
 * 对各候选物资执行 Min-Max 归一化后计算加权综合得分并降序排序。
 * wTimeCoin 独立于六维体系，仅在志愿者抢单路径生效。
 */
@Slf4j
@Component
public class MultiFactorDispatchStrategy {

    @Autowired
    private IConfigService configService;

    /**
     * 对候选物资/驿站执行 Min-Max 归一化并计算 SAW 综合得分，用于系统端自动指派。
     *
     * @param candidates   候选驿站及物资列表
     * @param orderUrgency 订单紧急度 (1-10)
     * @return 按 finalScore 降序排列的候选列表
     */
    public List<DispatchCandidateVO> calculateAndRank(List<DispatchCandidateVO> candidates, int orderUrgency) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        Config activeConfig = configService.getCurrentConfig();
        double wDist = activeConfig.getWDist().doubleValue();
        double wUrgency = activeConfig.getWUrgency().doubleValue();
        double wStock = activeConfig.getWStock() != null ? activeConfig.getWStock().doubleValue() : 0.10;
        double wExpiration = activeConfig.getWExpiration() != null ? activeConfig.getWExpiration().doubleValue() : 0.10;

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
     * 志愿者抢单大厅排序（默认 timeCoin=0）。
     * 委托 {@link #rankOrdersForVolunteer(List, Double, Double, int, int)} 执行。
     */
    public void rankOrdersForVolunteer(List<AvailableOrderVO> orders, Double volLon, Double volLat, int volCredit) {
        rankOrdersForVolunteer(orders, volLon, volLat, volCredit, 0);
    }

    /**
     * 志愿者抢单大厅排序策略。
     *
     * 计算志愿者到各订单起点的接驾距离 (Haversine)，结合紧急度、信誉分及时间币，
     * 经 Min-Max 归一化后加权求和得到 matchScore，实现千人千面的个性化订单排序。
     *
     * @param orders    待排序订单列表
     * @param volLon    志愿者经度
     * @param volLat    志愿者纬度
     * @param volCredit 志愿者信誉分 (0-150)
     * @param timeCoin  志愿者时间币 (0-50)
     */

    public void rankOrdersForVolunteer(List<AvailableOrderVO> orders, Double volLon, Double volLat, int volCredit, int timeCoin) {
        if (orders == null || orders.isEmpty() || volLon == null || volLat == null) {
            return;
        }

        Config activeConfig = configService.getCurrentConfig();
        double wDist = activeConfig.getWDist().doubleValue();
        double wUrgency = activeConfig.getWUrgency().doubleValue();
        double wCredit = activeConfig.getWCredit().doubleValue();
        double wTimeCoin = activeConfig.getWTimeCoin() != null ? activeConfig.getWTimeCoin().doubleValue() : 0.05;

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
            // D. 时间币赋能 (累计服务时长越多的志愿者优先级越高)
            double normTimeCoin = Math.min(timeCoin / 50.0, 1.0);

            double finalScore = (normDist * wDist) + (normUrgency * wUrgency) + (normCredit * wCredit) + (normTimeCoin * wTimeCoin);
            order.setMatchScore(finalScore);
        }

        // 3. 按照得分从高到低排序
        orders.sort(Comparator.comparing(AvailableOrderVO::getMatchScore).reversed());
    }

    /**
     * Haversine 球面距离公式 (km)，用于无高德 API 覆盖时的距离估算降级。
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