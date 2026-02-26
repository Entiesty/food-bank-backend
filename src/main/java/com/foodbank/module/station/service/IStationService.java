package com.foodbank.module.station.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.foodbank.module.station.entity.Station;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;

/**
 * 物资据点服务类接口
 */
public interface IStationService extends IService<Station> {

    /**
     * 核心算法 1：粗筛 - 查找指定半径内的所有据点
     *
     * @param longitude 受赠方所在经度
     * @param latitude  受赠方所在纬度
     * @param radius    搜索半径（千米）
     * @return 附近的据点 ID 及直线距离信息
     */
    GeoResults<RedisGeoCommands.GeoLocation<String>> searchNearbyStations(Double longitude, Double latitude, double radius);
}