package com.foodbank.module.dispatch.model.vo;

import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.station.entity.Station;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 调度候选据点及物资视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchCandidateVO {

    // 候选据点信息
    private Station station;

    // 匹配到的具体可用物资
    private Goods goods;

    // 距离求助者的真实距离（米）
    private Long distance;

    // 骑行预计耗时（秒）- 接收高德地图返回的时间
    private Long duration;

    // 当前可用库存
    private int currentStock;

    // 最终计算出的综合加权得分
    private double finalScore;
}