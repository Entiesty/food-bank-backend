package com.foodbank.module.resource.station.service;

import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;

public interface IStationGeoService {

    /**
     * 将全量据点坐标从 MySQL 同步到 Redis Geo
     */
    void syncStationsToGeo();

    /**
     * 核心 LBS 检索：查找指定坐标半径内的据点
     * @param lon 经度
     * @param lat 纬度
     * @param radiusKm 搜索半径(公里)
     * @return 包含距离信息的据点列表
     */
    GeoResults<RedisGeoCommands.GeoLocation<String>> getNearbyStations(double lon, double lat, double radiusKm);
}