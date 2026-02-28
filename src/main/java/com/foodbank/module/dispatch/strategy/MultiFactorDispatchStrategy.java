package com.foodbank.module.dispatch.strategy;

import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import com.foodbank.module.system.entity.Config;
import com.foodbank.module.system.service.IConfigService;
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
     * 对候选物资/据点进行综合打分并排序
     *
     * @param candidates 初筛后的候选列表 (包含据点、物资、距离)
     * @param orderUrgency 当前订单的紧急度 (1-10)
     * @return 按得分从高到低排序的最佳匹配列表
     */
    public List<DispatchCandidateVO> calculateAndRank(List<DispatchCandidateVO> candidates, int orderUrgency) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        // 1. 实时拉取数据库最新的动态权重配置 (支持平急两用热切换)
        // 假设 id=1 是当前生效的全局配置
        Config activeConfig = configService.getById(1);
        double wDist = activeConfig.getWDist().doubleValue();
        double wUrgency = activeConfig.getWUrgency().doubleValue();
        // 扩展权重：假设剩余权重分配给物资自身属性(库存0.1、临期0.1等，可根据实际情况再到数据库扩充字段)
        double wStock = 0.10;
        double wExpiration = 0.10;

        // 2. 寻找极值用于归一化处理 (Min-Max Normalization)
        long maxDistance = candidates.stream().mapToLong(DispatchCandidateVO::getDistance).max().orElse(1L);
        long minDistance = candidates.stream().mapToLong(DispatchCandidateVO::getDistance).min().orElse(0L);
        long distanceRange = Math.max(maxDistance - minDistance, 1L);

        int maxStock = candidates.stream().mapToInt(DispatchCandidateVO::getCurrentStock).max().orElse(1);
        int minStock = candidates.stream().mapToInt(DispatchCandidateVO::getCurrentStock).min().orElse(0);
        int stockRange = Math.max(maxStock - minStock, 1);

        // 提取临期时间的极值 (转换为时间戳秒数)
        long maxExpTime = candidates.stream()
                .mapToLong(c -> c.getGoods().getExpirationDate().toEpochSecond(ZoneOffset.UTC))
                .max().orElse(1L);
        long minExpTime = candidates.stream()
                .mapToLong(c -> c.getGoods().getExpirationDate().toEpochSecond(ZoneOffset.UTC))
                .min().orElse(0L);
        long expRange = Math.max(maxExpTime - minExpTime, 1L);

        // 订单紧急度归一化 (上限默认 10 级)
        double normUrgency = orderUrgency / 10.0;

        // 3. 遍历计算每个据点/物资的综合得分
        for (DispatchCandidateVO candidate : candidates) {

            // 因子 A：距离 (成本因子，越小越好) -> 反向归一化
            double normDistance = (double) (maxDistance - candidate.getDistance()) / distanceRange;

            // 因子 B：库存 (效益因子，越大越好) -> 正向归一化
            double normStock = (double) (candidate.getCurrentStock() - minStock) / stockRange;

            // 因子 C：临期时间 (成本因子，离过期越近越需要尽早发走) -> 反向归一化
            long currentExpTime = candidate.getGoods().getExpirationDate().toEpochSecond(ZoneOffset.UTC);
            double normExpiration = (double) (maxExpTime - currentExpTime) / expRange;

            // 因子 D：应急属性补偿 (平急两用体现)
            // 如果订单紧急度>=8，且当前据点是核心应急调度站，给予 1.0 的满分补偿（再乘以紧急度权重）
            double emergencyBonus = (orderUrgency >= 8 && candidate.getStation().getIsEmergencyHub() == 1) ? 1.0 : 0.0;

            // 核心加权公式
            double finalScore = (normDistance * wDist)
                    + (normStock * wStock)
                    + (normExpiration * wExpiration)
                    + ((normUrgency + emergencyBonus) * wUrgency); // 紧急订单匹配应急站会有极大加分

            candidate.setFinalScore(finalScore);

            log.debug("🎯 候选评测 [{}-{}] | 距离:{}m(分:{}), 临期:{}秒(分:{}), 应急站:{}(分:{}) => 总分: {}",
                    candidate.getStation().getStationName(), candidate.getGoods().getGoodsName(),
                    candidate.getDistance(), String.format("%.2f", normDistance * wDist),
                    currentExpTime, String.format("%.2f", normExpiration * wExpiration),
                    candidate.getStation().getIsEmergencyHub(), String.format("%.2f", emergencyBonus * wUrgency),
                    String.format("%.4f", finalScore));
        }

        // 4. 按最终得分降序排序 (分数最高的排在最前面，即“最优解”)
        candidates.sort(Comparator.comparing(DispatchCandidateVO::getFinalScore).reversed());

        return candidates;
    }
}