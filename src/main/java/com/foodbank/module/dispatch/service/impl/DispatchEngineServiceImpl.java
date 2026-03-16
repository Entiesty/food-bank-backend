package com.foodbank.module.dispatch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.dispatch.model.dto.AmapDirectionResponse;
import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import com.foodbank.module.dispatch.amap.AmapClientService;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import com.foodbank.module.trade.task.service.IDeliveryTaskService;
import com.foodbank.module.dispatch.strategy.MultiFactorDispatchStrategy;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.service.IGoodsService;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.service.IStationService;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.IUserService;
import com.foodbank.module.trade.order.mapper.DispatchOrderMapper;
import com.foodbank.module.resource.goods.mapper.GoodsMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DispatchEngineServiceImpl {

    @Autowired
    private IStationService stationService;
    @Autowired
    private IGoodsService goodsService;
    @Autowired
    private AmapClientService amapClientService;
    @Autowired
    private MultiFactorDispatchStrategy dispatchStrategy;

    @Autowired
    private IDispatchOrderService orderService;
    @Autowired
    private IDeliveryTaskService taskService;
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 🚨 注入新增加的自定义 Mapper
    @Autowired
    private DispatchOrderMapper dispatchOrderMapper;
    @Autowired
    private GoodsMapper goodsMapper;

    public List<DispatchCandidateVO> smartMatchStations(DispatchOrder dispatchOrder) {
        log.info("📡 启动智能派单匹配，坐标:[{},{}], 需求大类:{}, 需求标签:{}",
                dispatchOrder.getTargetLon(), dispatchOrder.getTargetLat(),
                dispatchOrder.getRequiredCategory(), dispatchOrder.getRequiredTags());

        // 1. 领域大类映射 (Domain Mapping)
        List<String> targetCategories = new ArrayList<>();
        targetCategories.add(dispatchOrder.getRequiredCategory());
        String reqCat = dispatchOrder.getRequiredCategory();

        if ("粮油副食".equals(reqCat)) targetCategories.addAll(Arrays.asList("米面粮油", "烘焙糕点", "速食品", "乳制品", "生鲜水果", "生鲜蔬菜", "生鲜冷冻"));
        else if ("医疗与特需".equals(reqCat)) targetCategories.addAll(Arrays.asList("医疗用品", "助残设备", "营养品"));
        else if ("应急与生活".equals(reqCat)) targetCategories.addAll(Arrays.asList("饮用水", "应急食品", "应急装备", "生活用品", "防寒衣物"));

        // 解析并清洗 JSON 格式的标签
        List<String> reqTags = new ArrayList<>();
        if (dispatchOrder.getRequiredTags() != null && !dispatchOrder.getRequiredTags().isEmpty()) {
            reqTags = Arrays.asList(dispatchOrder.getRequiredTags().replaceAll("[\"\\[\\]\\s]", "").split(","));
        }
        boolean hasTagRequirement = !reqTags.isEmpty();
        String originLonLat = dispatchOrder.getTargetLon() + "," + dispatchOrder.getTargetLat();
        List<DispatchCandidateVO> candidates = new ArrayList<>();

        // ==========================================================
        // 🚀 L0级：战时点对点直达匹配 (Point-to-Point)
        // ==========================================================
        List<DispatchOrder> directSupplyOrders = dispatchOrderMapper.selectPendingDirectSupplyOrders(targetCategories);

        for (DispatchOrder supply : directSupplyOrders) {
            Goods goods = goodsService.getById(supply.getGoodsId());
            if (goods == null || goods.getStock() <= 0) continue;

            User merchant = userService.getById(supply.getSourceId());
            if (merchant == null || merchant.getCurrentLon() == null) continue;

            double currentTagScore = calculateTagScore(hasTagRequirement, reqTags, goods.getTags());
            if (hasTagRequirement && currentTagScore == 0.0) continue;

            String destLonLat = merchant.getCurrentLon() + "," + merchant.getCurrentLat();
            try {
                AmapDirectionResponse.Path path = amapClientService.getRidingDistance(originLonLat, destLonLat);
                if (path.distance() > 15000) continue; // 超出15km直接舍弃

                // 💡 架构师黑魔法：将商家伪装成“虚拟站点”，ID取负数。这样不改表结构就能兼容现有骑士履约闭环
                Station fakeStation = new Station();
                fakeStation.setStationId(-merchant.getUserId());
                fakeStation.setStationName("🚨紧急直发: " + merchant.getUsername());
                fakeStation.setLongitude(merchant.getCurrentLon());
                fakeStation.setLatitude(merchant.getCurrentLat());
                fakeStation.setAddress("由爱心商铺直线护送");

                candidates.add(DispatchCandidateVO.builder()
                        .station(fakeStation).goods(goods)
                        .distance(path.distance()).duration(path.duration())
                        .currentStock(goods.getStock()).build());
            } catch (Exception e) {
                log.error("L0路线规划失败", e);
            }
        }

        if (!candidates.isEmpty()) {
            log.info("🔥 L0级直达匹配成功！发现 {} 个紧急供应源，将直接指派骑士越过驿站！", candidates.size());
            return dispatchStrategy.calculateAndRank(candidates, dispatchOrder.getUrgencyLevel());
        }

        // ==========================================================
        // 🚚 L1级：平时中转站调度匹配 (Hub-and-Spoke)
        // ==========================================================
        log.info("ℹ️ L0级直达无匹配，降级进入 L1 驿站中转寻源...");
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults =
                stationService.searchNearbyStations(dispatchOrder.getTargetLon().doubleValue(), dispatchOrder.getTargetLat().doubleValue(), 15.0);

        if (geoResults != null && !geoResults.getContent().isEmpty()) {
            for (var result : geoResults.getContent()) {
                Long stationId = Long.parseLong(result.getContent().getName());

                List<Goods> stationGoodsList = goodsMapper.selectAvailableGoodsByStation(stationId, targetCategories);
                Goods bestMatchedGoods = null;
                double maxTagScore = -1.0;

                for (Goods goods : stationGoodsList) {
                    double currentTagScore = calculateTagScore(hasTagRequirement, reqTags, goods.getTags());
                    if (currentTagScore > maxTagScore) {
                        maxTagScore = currentTagScore;
                        bestMatchedGoods = goods;
                    }
                }

                if (bestMatchedGoods == null) continue;
                Station station = stationService.getById(stationId);
                if (station == null) continue;

                String destLonLat = station.getLongitude() + "," + station.getLatitude();
                try {
                    AmapDirectionResponse.Path path = amapClientService.getRidingDistance(originLonLat, destLonLat);
                    candidates.add(DispatchCandidateVO.builder()
                            .station(station).goods(bestMatchedGoods)
                            .distance(path.distance()).duration(path.duration())
                            .currentStock(bestMatchedGoods.getStock()).build());
                } catch (Exception e) {}
            }
        }

        if (candidates.isEmpty()) return new ArrayList<>();
        return dispatchStrategy.calculateAndRank(candidates, dispatchOrder.getUrgencyLevel());
    }

    // JSON 标签安全算分引擎
    private double calculateTagScore(boolean hasReq, List<String> reqTags, String dbTagsJson) {
        if (!hasReq) return 1.0;
        if (dbTagsJson == null || dbTagsJson.isEmpty()) return 0.0;
        String cleanTags = dbTagsJson.replaceAll("[\"\\[\\]\\s]", "");
        List<String> goodsTags = Arrays.asList(cleanTags.split(","));
        long matchCount = reqTags.stream().filter(t -> goodsTags.contains(t.trim())).count();
        if (matchCount == 0) return 0.0;
        return (double) matchCount / reqTags.size();
    }

    @Transactional(rollbackFor = Exception.class)
    public void grabOrder(Long orderId, Long volunteerId) {
        if (orderId == null || volunteerId == null) throw new BusinessException("订单ID或志愿者ID不能为空");

        User volunteer = userService.getById(volunteerId);
        if (volunteer == null) throw new BusinessException("志愿者身份异常，请重新登录");
        if (volunteer.getIsVerified() == null || volunteer.getIsVerified() == 0) {
            throw new BusinessException("您的资质尚未通过审核，暂无接单权限！");
        }

        DispatchOrder order = orderService.getById(orderId);
        if (order == null) throw new BusinessException("该订单不存在");

        Integer vType = volunteer.getVehicleType() != null ? volunteer.getVehicleType() : 1;

        // 🚨 距离拦截
        if (order.getTargetLat() != null && order.getTargetLon() != null
                && order.getSourceLat() != null && order.getSourceLon() != null) {
            double dist = calculateDistance(order.getSourceLat().doubleValue(), order.getSourceLon().doubleValue(),
                    order.getTargetLat().doubleValue(), order.getTargetLon().doubleValue());
            if (dist > 5.0 && vType <= 2) {
                throw new BusinessException("🚨 运力不匹配：该单跨区较远(" + String.format("%.1f", dist) + "km)，请移交机动骑士！");
            }
        }

        // 🚨 CVRP 累计装载算力预警
        if (order.getGoodsId() != null) {
            Goods newGoods = goodsService.getById(order.getGoodsId());
            if (newGoods != null) {
                int maxVolumePoints = vType == 1 ? 2 : (vType == 2 ? 5 : (vType == 3 ? 15 : 100));
                int maxWeightPoints = vType == 1 ? 2 : (vType == 2 ? 4 : (vType == 3 ? 10 : 100));

                int currentVolumePoints = 0;
                int currentWeightPoints = 0;

                List<DeliveryTask> activeTasks = taskService.list(new LambdaQueryWrapper<DeliveryTask>()
                        .eq(DeliveryTask::getVolunteerId, volunteerId)
                        .in(DeliveryTask::getTaskStatus, Arrays.asList(1, 2)));

                for (DeliveryTask t : activeTasks) {
                    DispatchOrder activeOrder = orderService.getById(t.getOrderId());
                    if (activeOrder != null && activeOrder.getGoodsId() != null) {
                        Goods g = goodsService.getById(activeOrder.getGoodsId());
                        if (g != null) {
                            currentVolumePoints += (g.getVolumeLevel() == 3 ? 40 : (g.getVolumeLevel() == 2 ? 5 : 1));
                            currentWeightPoints += (g.getWeightLevel() == 3 ? 20 : (g.getWeightLevel() == 2 ? 5 : 1));
                        }
                    }
                }

                int newVolumePoint = newGoods.getVolumeLevel() == 3 ? 40 : (newGoods.getVolumeLevel() == 2 ? 5 : 1);
                int newWeightPoint = newGoods.getWeightLevel() == 3 ? 20 : (newGoods.getWeightLevel() == 2 ? 5 : 1);

                if ((currentVolumePoints + newVolumePoint) > maxVolumePoints) {
                    throw new BusinessException("🚨 爆仓预警：您的载具容量已达极限！请先完成当前配送！");
                }
                if ((currentWeightPoints + newWeightPoint) > maxWeightPoints) {
                    throw new BusinessException("🚨 超载预警：继续接单将超出您的载具安全承重，已强行熔断！");
                }
            }
        }

        boolean isGrabbed = orderService.update(
                new LambdaUpdateWrapper<DispatchOrder>().eq(DispatchOrder::getOrderId, orderId)
                        .eq(DispatchOrder::getStatus, 0).set(DispatchOrder::getStatus, 1)
        );

        if (!isGrabbed) throw new BusinessException("晚了一小步，该任务已被领取！");

        DeliveryTask deliveryTask = new DeliveryTask();
        deliveryTask.setOrderId(orderId);
        deliveryTask.setVolunteerId(volunteerId);
        deliveryTask.setTaskStatus((byte) 1);
        deliveryTask.setVersion(0);
        taskService.save(deliveryTask);
    }

    public void pickUpGoods(Long taskId) {
        DeliveryTask deliveryTask = taskService.getById(taskId);
        if (deliveryTask == null || deliveryTask.getTaskStatus() != 1) throw new BusinessException("任务状态异常");
        deliveryTask.setTaskStatus((byte) 2);
        if (!taskService.updateById(deliveryTask)) throw new BusinessException("操作冲突，请重试");
    }

    public Map<String, Object> triggerEmergencyBroadcast(Long orderId) {
        // 🛡️ 架构师防线 4：分布式防重放锁 (防止管理员手抖狂点)
        String lockKey = "LOCK:BROADCAST:" + orderId;
        Boolean isLocked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 30, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(isLocked)) {
            throw new BusinessException("⚠️ 正在向全城发射紧急广播信号，请勿在 30 秒内频繁点击！");
        }

        DispatchOrder order = orderService.getById(orderId);
        if (order == null || order.getTargetLon() == null) throw new BusinessException("坐标缺失");

        // (后续大厂优化点：这里的 userService.list 未来量大时必须换成 ST_Distance 空间索引SQL查询)
        List<User> allMerchants = userService.list(new LambdaQueryWrapper<User>().eq(User::getRole, 2).isNotNull(User::getCurrentLon));
        List<User> list3km = new ArrayList<>(), list10km = new ArrayList<>();

        for (User m : allMerchants) {
            double dist = calculateDistance(order.getTargetLat().doubleValue(), order.getTargetLon().doubleValue(),
                    m.getCurrentLat().doubleValue(), m.getCurrentLon().doubleValue());
            if (dist <= 3.0) list3km.add(m);
            if (dist <= 10.0) list10km.add(m);
        }

        List<User> targetMerchants;
        Map<String, Object> result = new HashMap<>();

        if (!list3km.isEmpty()) {
            targetMerchants = list3km; result.put("radius", 3); result.put("isDegraded", false);
        } else if (!list10km.isEmpty()) {
            targetMerchants = list10km; result.put("radius", 10); result.put("isDegraded", true);
        } else {
            // 失败也需手动释放防抖锁，否则要等 30 秒
            stringRedisTemplate.delete(lockKey);
            throw new BusinessException("终极熔断：扩大至全城 10 公里均无商铺响应！");
        }

        for (User m : targetMerchants) {
            String redisKey = "EMERGENCY_BCAST:" + m.getUserId();
            stringRedisTemplate.opsForValue().set(redisKey, order.getRequiredCategory() + "|" + order.getOrderId(), 60, TimeUnit.SECONDS);
        }

        result.put("notifiedCount", targetMerchants.size());
        return result;
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}