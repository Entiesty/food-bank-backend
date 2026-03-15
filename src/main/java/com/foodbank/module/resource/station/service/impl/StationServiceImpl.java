package com.foodbank.module.resource.station.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.constant.RedisKeyConstant;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.mapper.StationMapper;
import com.foodbank.module.resource.station.model.vo.StationRecommendVO;
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
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 物资据点服务实现类 (整合 Redis GEO 空间算法)
 */
@Slf4j
@Service
public class StationServiceImpl extends ServiceImpl<StationMapper, Station> implements IStationService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean addStationAndSyncGeo(Station station) {
        boolean saved = this.save(station);
        if (!saved) throw new BusinessException("新增据点失败");

        if (station.getLongitude() != null && station.getLatitude() != null) {
            try {
                Point point = new Point(station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
                stringRedisTemplate.opsForGeo().add(
                        RedisKeyConstant.STATION_GEO_KEY,
                        point,
                        String.valueOf(station.getStationId())
                );
                log.info("🌐 新增据点 [{}] 成功，已实时同步至 Redis Geo 缓存池", station.getStationName());
            } catch (Exception e) {
                log.error("🚨 同步据点至 Redis Geo 失败: {}", e.getMessage());
                throw new BusinessException("地理位置缓存同步失败，请检查系统状态");
            }
        }
        return true;
    }

    @Override
    public List<StationRecommendVO> getRecommendStations(Double userLon, Double userLat) {
        List<Station> stations = this.list();
        List<StationRecommendVO> voList = new ArrayList<>();

        for (Station st : stations) {
            StationRecommendVO vo = new StationRecommendVO();
            vo.setStationId(st.getStationId());
            vo.setStationName(st.getStationName());
            vo.setAddress(st.getAddress());

            // 👇👇👇 🚨 核心修复：把数据库查出来的冷链和应急属性赋值给 VO
            vo.setHasFreezer(st.getHasFreezer());
            vo.setIsEmergencyHub(st.getIsEmergencyHub());
            // 👆👆👆

            // 🚨 真实 LBS 计算：如果前端传了商家的经纬度，且驿站也有经纬度
            if (userLon != null && userLat != null && st.getLongitude() != null && st.getLatitude() != null) {
                double dist = calculateDistance(userLat, userLon, st.getLatitude().doubleValue(), st.getLongitude().doubleValue());
                vo.setRawDistance(dist);
                vo.setDistance(String.format("%.2f", dist) + "km");
            } else {
                // 如果没有拿到定位，距离未知，放到列表最后面
                vo.setRawDistance(Double.MAX_VALUE);
                vo.setDistance("距离未知");
            }
            voList.add(vo);
        }

        // 按真实物理距离从近到远排序
        voList.sort(Comparator.comparing(StationRecommendVO::getRawDistance));

        return voList;
    }

    /**
     * 📐 经典的 Haversine 公式：计算地球上两点之间的真实直线距离 (单位: 公里)
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // 地球半径(km)
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateStationAndSyncGeo(Station station) {
        boolean updated = this.updateById(station);
        if (!updated) throw new BusinessException("更新据点失败");

        if (station.getLongitude() != null && station.getLatitude() != null) {
            // 重新往 Geo 里 add 相同的 member (stationId)，会自动覆盖旧的经纬度
            Point point = new Point(station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
            stringRedisTemplate.opsForGeo().add(
                    RedisKeyConstant.STATION_GEO_KEY,
                    point,
                    String.valueOf(station.getStationId())
            );
        }
        return true;
    }
}