package com.foodbank.module.resource.station.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.model.vo.StationRecommendVO;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;

import java.util.List;

/**
 * 物资据点服务类接口
 */
public interface IStationService extends IService<Station> {

    GeoResults<RedisGeoCommands.GeoLocation<String>> searchNearbyStations(Double longitude, Double latitude, double radius);

    boolean addStationAndSyncGeo(Station station);

    /**
     * 获取带有真实 LBS 距离的驿站列表
     */
    List<StationRecommendVO> getRecommendStations(Double lon, Double lat);

    /**
     * 更新物理据点信息，并同步更新 Redis Geo 中的经纬度坐标
     * @param station 据点实体
     * @return 是否成功
     */
    boolean updateStationAndSyncGeo(Station station);
}