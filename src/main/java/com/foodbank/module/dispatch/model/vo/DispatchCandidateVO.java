package com.foodbank.module.dispatch.model.vo;

import com.foodbank.module.station.entity.Station;
import lombok.Builder;
import lombok.Data;

/**
 * 多因子决策候选据点视图对象
 */
@Data
@Builder
public class DispatchCandidateVO {

    private Station station;

    // 真实骑行距离 (米)
    private Long distance;

    // 预计骑行耗时 (秒)
    private Long duration;

    // 当前该物资的库存量
    private Integer currentStock;

    // 综合加权得分 (决定最终派单给谁)
    private Double finalScore;
}