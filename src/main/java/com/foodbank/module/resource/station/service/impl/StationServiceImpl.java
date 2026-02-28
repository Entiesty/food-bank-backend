package com.foodbank.module.resource.station.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.constant.RedisKeyConstant;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.mapper.StationMapper;
import com.foodbank.module.resource.station.service.IStationService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     */
    @PostConstruct
    public void initStationGeoToRedis() {
        log.info("开始同步据点地理位置信息到 Redis GEO...");
        List<Station> stationList = this.list();
        if (stationList == null || stationList.isEmpty()) {
            return;
        }

        List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
        for (Station station : stationList) {
            Point point = new Point(station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
            locations.add(new RedisGeoCommands.GeoLocation<>(String.valueOf(station.getStationId()), point));
        }

        stringRedisTemplate.opsForGeo().add(RedisKeyConstant.STATION_GEO_KEY, locations);
        log.info("Redis GEO 数据预热完成，共加载 {} 个据点", locations.size());
    }

    @Override
    public GeoResults<RedisGeoCommands.GeoLocation<String>> searchNearbyStations(Double longitude, Double latitude, double radius) {
        Point centerPoint = new Point(longitude, latitude);
        Distance searchDistance = new Distance(radius, Metrics.KILOMETERS);

        RedisGeoCommands.GeoSearchCommandArgs args = RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                .includeDistance()
                .sortAscending()
                .limit(10);

        return stringRedisTemplate.opsForGeo().search(
                RedisKeyConstant.STATION_GEO_KEY,
                GeoReference.fromCoordinate(centerPoint),
                searchDistance,
                args
        );
    }

    /**
     * 🚨 核心双写同步逻辑：新增据点
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean addStationAndSyncGeo(Station station) {
        // 1. 先落库 MySQL
        boolean saved = this.save(station);
        if (!saved) {
            throw new BusinessException("新增据点失败");
        }

        // 2. 立即同步坐标到 Redis Geo 缓存池
        if (station.getLongitude() != null && station.getLatitude() != null) {
            try {
                Point point = new Point(station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
                // 注意：因为上面注入的是 StringRedisTemplate，所以 Member 必须转化为 String
                stringRedisTemplate.opsForGeo().add(
                        RedisKeyConstant.STATION_GEO_KEY,
                        point,
                        String.valueOf(station.getStationId())
                );
                log.info("🌐 新增据点 [{}] 成功，已实时同步至 Redis Geo 缓存池", station.getStationName());
            } catch (Exception e) {
                log.error("🚨 同步据点至 Redis Geo 失败: {}", e.getMessage());
                // 抛出异常触发 @Transactional 回滚，确保强一致性
                throw new BusinessException("地理位置缓存同步失败，请检查系统状态");
            }
        }
        return true;
    }
}