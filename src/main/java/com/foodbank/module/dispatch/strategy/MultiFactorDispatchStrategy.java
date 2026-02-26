package com.foodbank.module.dispatch.strategy;

import com.foodbank.module.dispatch.config.DispatchProperties;
import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 核心：多因子加权调度决策算法
 */
@Slf4j
@Component
public class MultiFactorDispatchStrategy {

    @Autowired
    private DispatchProperties properties;

    /**
     * 对候选据点进行综合打分并排序
     *
     * @param candidates 粗筛后并已获取真实距离的候选据点列表
     * @param orderUrgency 当前订单的紧急度 (1-10)
     * @return 按得分从高到低排序的最佳匹配列表
     */
    public List<DispatchCandidateVO> calculateAndRank(List<DispatchCandidateVO> candidates, int orderUrgency) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        // 1. 寻找极值用于归一化处理 (Min-Max Normalization)
        long maxDistance = candidates.stream().mapToLong(DispatchCandidateVO::getDistance).max().orElse(1L);
        long minDistance = candidates.stream().mapToLong(DispatchCandidateVO::getDistance).min().orElse(0L);

        int maxStock = candidates.stream().mapToInt(DispatchCandidateVO::getCurrentStock).max().orElse(1);
        int minStock = candidates.stream().mapToInt(DispatchCandidateVO::getCurrentStock).min().orElse(0);

        // 防御性处理，避免分母为 0
        long distanceRange = Math.max(maxDistance - minDistance, 1L);
        int stockRange = Math.max(maxStock - minStock, 1);

        // 订单紧急度归一化 (上限默认设为 10 级)
        double normUrgency = orderUrgency / 10.0;

        // 2. 遍历计算每个据点的综合得分
        for (DispatchCandidateVO candidate : candidates) {

            // 距离是成本因子 (越小越好)，所以用反向归一化: (最大值 - 当前值) / 极差
            double normDistance = (double) (maxDistance - candidate.getDistance()) / distanceRange;

            // 库存是效益因子 (越大越好)，正向归一化: (当前值 - 最小值) / 极差
            double normStock = (double) (candidate.getCurrentStock() - minStock) / stockRange;

            // 核心加权公式：归一化值 * 权重
            double finalScore = (normDistance * properties.getDistance())
                    + (normStock * properties.getStock())
                    + (normUrgency * properties.getUrgency());

            candidate.setFinalScore(finalScore);

            log.debug("据点 [{}] 计算明细 -> 距离得分: {}, 库存得分: {}, 紧急度得分: {} | 总分: {}",
                    candidate.getStation().getStationName(),
                    String.format("%.4f", normDistance * properties.getDistance()),
                    String.format("%.4f", normStock * properties.getStock()),
                    String.format("%.4f", normUrgency * properties.getUrgency()),
                    String.format("%.4f", finalScore));
        }

        // 3. 按最终得分降序排序 (分数最高的排在最前面)
        candidates.sort(Comparator.comparing(DispatchCandidateVO::getFinalScore).reversed());

        return candidates;
    }
}