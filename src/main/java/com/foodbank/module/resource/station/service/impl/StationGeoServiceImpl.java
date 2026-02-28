package com.foodbank.module.resource.station.service.impl;

import com.foodbank.common.constant.RedisKeyConstant;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.service.IStationGeoService;
import com.foodbank.module.resource.station.service.IStationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.util.List;

@Slf4j
@Service
public class StationGeoServiceImpl implements IStationGeoService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IStationService stationService;

    @Override
    @PostConstruct // 💡 亮点：Spring容器启动完成后，自动执行同步！
    public void syncStationsToGeo() {
        log.info("⏳ 正在同步据点地理位置到 Redis Geo...");
        List<Station> stations = stationService.list();
        if (stations == null || stations.isEmpty()) {
            log.warn("⚠️ 数据库中没有据点数据，跳过同步。");
            return;
        }

        String key = RedisKeyConstant.STATION_GEO_KEY; //

        // 同步前先清理旧缓存，防止产生脏数据
        stringRedisTemplate.delete(key);

        int count = 0;
        for (Station station : stations) {
            if (station.getLongitude() != null && station.getLatitude() != null) {
                Point point = new Point(station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
                // 将 stationId 作为 value 存入 Geo 集合
                stringRedisTemplate.opsForGeo().add(key, point, String.valueOf(station.getStationId()));
                count++;
            }
        }
        log.info("✅ 成功同步 {} 个据点到 Redis Geo 缓存池.", count);
    }

    @Override
    public GeoResults<RedisGeoCommands.GeoLocation<String>> getNearbyStations(double lon, double lat, double radiusKm) {
        String key = RedisKeyConstant.STATION_GEO_KEY;
        Point center = new Point(lon, lat);
        Distance distance = new Distance(radiusKm, Metrics.KILOMETERS);

        // 使用 Spring Boot 3 新版 GeoSearch API
        RedisGeoCommands.GeoSearchCommandArgs args = RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                .includeDistance() // 必须包含距离信息，后续加权算法需要用到
                .sortAscending();  // 默认按距离由近到远初步排序

        return stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(center),
                distance,
                args
        );
    }
}