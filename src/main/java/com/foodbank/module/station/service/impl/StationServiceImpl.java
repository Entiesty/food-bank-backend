package com.foodbank.module.station.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.constant.RedisKeyConstant;
import com.foodbank.module.station.entity.Station;
import com.foodbank.module.station.mapper.StationMapper;
import com.foodbank.module.station.service.IStationService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 物资据点服务实现类 (整合 Redis GEO 空间算法)
 */
@Slf4j
@Service
public class StationServiceImpl extends ServiceImpl<StationMapper, Station> implements IStationService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 项目启动时自动执行：缓存预热 (Cache Warming)
     * 将数据库中所有正常的据点坐标同步到 Redis 中
     */
    @PostConstruct
    public void initStationGeoToRedis() {
        log.info("开始同步据点地理位置信息到 Redis GEO...");
        // 1. 从 MySQL 查询所有据点
        List<Station> stationList = this.list();
        if (stationList == null || stationList.isEmpty()) {
            return;
        }

        // 2. 批量添加到 Redis (避免 for 循环里单条 add 造成多次网络开销)
        List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
        for (Station station : stationList) {
            // 经度在前，纬度在后
            Point point = new Point(station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
            // member 我们存站点的 ID
            locations.add(new RedisGeoCommands.GeoLocation<>(String.valueOf(station.getStationId()), point));
        }

        // 3. 写入 Redis
        stringRedisTemplate.opsForGeo().add(RedisKeyConstant.STATION_GEO_KEY, locations);
        log.info("Redis GEO 数据预热完成，共加载 {} 个据点", locations.size());
    }

    /**
     * 核心算法 1：粗筛 - 查找指定半径内的所有据点
     * * @param longitude 受赠方所在经度
     * @param latitude  受赠方所在纬度
     * @param radius    搜索半径（千米）
     * @return 附近的据点 ID 及直线距离信息
     */
    public GeoResults<RedisGeoCommands.GeoLocation<String>> searchNearbyStations(Double longitude, Double latitude, double radius) {

        // 构建搜索中心点
        Point centerPoint = new Point(longitude, latitude);

        // 构建搜索距离对象 (Metrics.KILOMETERS 表示单位为千米)
        Distance searchDistance = new Distance(radius, Metrics.KILOMETERS);

        // 构建搜索参数 (包含距离，从近到远排序，限制返回数量防止爆内存)
        RedisGeoCommands.GeoSearchCommandArgs args = RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                .includeDistance() // 返回包含结果到中心点的距离
                .sortAscending()   // 按距离从近到远排序
                .limit(10);        // 最多只取最近的 10 个据点做粗筛候选

        // 执行 GEOSEARCH (注意：这是 Redis 6.2 之后的现代 API，取代了旧版的 GEORADIUS)
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                RedisKeyConstant.STATION_GEO_KEY,
                GeoReference.fromCoordinate(centerPoint),
                searchDistance,
                args
        );

        return results;
    }
}