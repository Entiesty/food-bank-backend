package com.foodbank.module.dispatch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.dispatch.model.dto.AmapDirectionResponse;
import com.foodbank.module.dispatch.model.dto.DispatchReqDTO;
import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import com.foodbank.module.dispatch.service.AmapClientService;
import com.foodbank.module.dispatch.strategy.MultiFactorDispatchStrategy;
import com.foodbank.module.goods.entity.Goods;
import com.foodbank.module.goods.service.IGoodsService;
import com.foodbank.module.station.entity.Station;
import com.foodbank.module.station.service.IStationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class DispatchOrderServiceImpl {

    @Autowired
    private IStationService stationService;

    @Autowired
    private IGoodsService goodsService; // 你之前用生成器生成的 Goods 服务

    @Autowired
    private AmapClientService amapClientService;

    @Autowired
    private MultiFactorDispatchStrategy dispatchStrategy;

    /**
     * 核心：一键智能匹配最优派发据点
     */
    public List<DispatchCandidateVO> smartMatchStations(DispatchReqDTO reqDTO) {
        log.info("接收到智能派单请求，坐标:[{},{}], 物资ID:{}, 紧急度:{}",
                reqDTO.getLongitude(), reqDTO.getLatitude(), reqDTO.getGoodsId(), reqDTO.getUrgency());

        // ================= 步骤 1：Redis GEO 粗筛 (5公里内) =================
        // 注：假设 stationService 中已经写好了上一节的 searchNearbyStations 方法
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults =
                stationService.searchNearbyStations(reqDTO.getLongitude(), reqDTO.getLatitude(), 5.0);

        if (geoResults == null || geoResults.getContent().isEmpty()) {
            throw new BusinessException("附近 5 公里内暂无可用食物银行据点");
        }

        // ================= 步骤 2：组装候选人数据并校验库存 =================
        List<DispatchCandidateVO> candidates = new ArrayList<>();
        String originLonLat = reqDTO.getLongitude() + "," + reqDTO.getLatitude();

        for (var result : geoResults.getContent()) {
            Long stationId = Long.parseLong(result.getContent().getName());

            // 查询该据点是否有请求的物资库存 (调用 MyBatis-Plus)
            Goods goods = goodsService.getOne(new LambdaQueryWrapper<Goods>()
                    .eq(Goods::getCurrentStationId, stationId)
                    .eq(Goods::getGoodsId, reqDTO.getGoodsId())
                    // status = 2 表示已入库
                    .eq(Goods::getStatus, 2));

            int currentStock = (goods != null && goods.getStock() != null) ? goods.getStock() : 0;

            // 如果库存为0，直接淘汰此据点，不参与后续高昂的 API 测算
            if (currentStock <= 0) {
                continue;
            }

            Station station = stationService.getById(stationId);
            if (station == null) continue;

            // ================= 步骤 3：高德 API 精算距离与耗时 =================
            String destLonLat = station.getLongitude() + "," + station.getLatitude();
            try {
                // 发起 HTTP 请求获取真实骑行数据
                AmapDirectionResponse.Path path = amapClientService.getRidingDistance(originLonLat, destLonLat);

                // 构建候选人视图对象
                DispatchCandidateVO candidate = DispatchCandidateVO.builder()
                        .station(station)
                        .distance(path.distance())
                        .duration(path.duration())
                        .currentStock(currentStock)
                        .build();
                candidates.add(candidate);

            } catch (Exception e) {
                log.warn("高德路径规划异常，据点ID: {} 暂不参与本次调度", stationId);
            }
        }

        if (candidates.isEmpty()) {
            throw new BusinessException("附近的据点均无库存或无法规划到达路线");
        }

        // ================= 步骤 4：多因子策略打分并降序排序 =================
        return dispatchStrategy.calculateAndRank(candidates, reqDTO.getUrgency());
    }
}