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

    public List<DispatchCandidateVO> smartMatchStations(DispatchOrder dispatchOrder) {
        log.info("📡 启动智能派单匹配，坐标:[{},{}], 需求大类:{}, 需求标签:{}",
                dispatchOrder.getTargetLon(), dispatchOrder.getTargetLat(),
                dispatchOrder.getRequiredCategory(), dispatchOrder.getRequiredTags());

        // 🚨 扩圈：同城级 15.0 公里
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults =
                stationService.searchNearbyStations(dispatchOrder.getTargetLon().doubleValue(), dispatchOrder.getTargetLat().doubleValue(), 15.0);

        if (geoResults == null || geoResults.getContent().isEmpty()) {
            return new ArrayList<>();
        }

        // 🚨 领域大类映射 (Domain Mapping)
        List<String> targetCategories = new ArrayList<>();
        targetCategories.add(dispatchOrder.getRequiredCategory());
        String reqCat = dispatchOrder.getRequiredCategory();

        if ("粮油副食".equals(reqCat)) targetCategories.addAll(Arrays.asList("米面粮油", "烘焙糕点", "速食品", "乳制品", "生鲜水果", "生鲜蔬菜", "生鲜冷冻"));
        else if ("医疗与特需".equals(reqCat)) targetCategories.addAll(Arrays.asList("医疗用品", "助残设备", "营养品"));
        else if ("应急与生活".equals(reqCat)) targetCategories.addAll(Arrays.asList("饮用水", "应急食品", "应急装备", "生活用品", "防寒衣物"));

        List<String> reqTags = new ArrayList<>();
        if (dispatchOrder.getRequiredTags() != null && !dispatchOrder.getRequiredTags().isEmpty()) {
            reqTags = Arrays.asList(dispatchOrder.getRequiredTags().split(","));
        }
        boolean hasTagRequirement = !reqTags.isEmpty();

        List<DispatchCandidateVO> candidates = new ArrayList<>();
        String originLonLat = dispatchOrder.getTargetLon() + "," + dispatchOrder.getTargetLat();

        for (var result : geoResults.getContent()) {
            Long stationId = Long.parseLong(result.getContent().getName());

            List<Goods> stationGoodsList = goodsService.list(new LambdaQueryWrapper<Goods>()
                    .eq(Goods::getCurrentStationId, stationId)
                    .in(Goods::getCategory, targetCategories)
                    .eq(Goods::getStatus, 2)
                    .gt(Goods::getStock, 0));

            Goods bestMatchedGoods = null;
            double maxTagScore = -1.0;

            for (Goods goods : stationGoodsList) {
                double currentTagScore = 1.0;
                if (hasTagRequirement) {
                    if (goods.getTags() == null || goods.getTags().isEmpty()) {
                        currentTagScore = 0.0;
                    } else {
                        List<String> goodsTags = Arrays.asList(goods.getTags().split(","));
                        long matchCount = reqTags.stream().filter(goodsTags::contains).count();
                        if (matchCount == 0) continue;
                        currentTagScore = (double) matchCount / reqTags.size();
                    }
                }
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

        if (candidates.isEmpty()) return new ArrayList<>();
        return dispatchStrategy.calculateAndRank(candidates, dispatchOrder.getUrgencyLevel());
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

        // 🚨 拦截 1：距离拦截（大于5km，步行/单车禁入）
        if (order.getTargetLat() != null && order.getTargetLon() != null
                && order.getSourceLat() != null && order.getSourceLon() != null) {
            double dist = calculateDistance(order.getSourceLat().doubleValue(), order.getSourceLon().doubleValue(),
                    order.getTargetLat().doubleValue(), order.getTargetLon().doubleValue());
            if (dist > 5.0 && vType <= 2) {
                throw new BusinessException("🚨 运力不匹配：该单跨区较远(" + String.format("%.1f", dist) + "km)，请移交机动骑士！");
            }
        }

        // 🚨🚨 拦截 2：引入 CVRP 累计装载算力 (Capacity Points) 🚨🚨
        if (order.getGoodsId() != null) {
            Goods newGoods = goodsService.getById(order.getGoodsId());
            if (newGoods != null) {
                // 1. 定义载具的【最大体积算力池】与【最大承重算力池】
                int maxVolumePoints = vType == 1 ? 2 : (vType == 2 ? 5 : (vType == 3 ? 15 : 100));
                int maxWeightPoints = vType == 1 ? 2 : (vType == 2 ? 4 : (vType == 3 ? 10 : 100));

                // 2. 累计骑手目前【正在执行中(taskStatus=1或2)】的订单负荷
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

                // 3. 加上当前准备抢的这单的负荷
                int newVolumePoint = newGoods.getVolumeLevel() == 3 ? 40 : (newGoods.getVolumeLevel() == 2 ? 5 : 1);
                int newWeightPoint = newGoods.getWeightLevel() == 3 ? 20 : (newGoods.getWeightLevel() == 2 ? 5 : 1);

                // 4. 终极爆仓校验
                if ((currentVolumePoints + newVolumePoint) > maxVolumePoints) {
                    throw new BusinessException("🚨 爆仓预警：您的载具容量已达极限！无法再顺路接载该体积的物资，请先完成当前配送！");
                }
                if ((currentWeightPoints + newWeightPoint) > maxWeightPoints) {
                    throw new BusinessException("🚨 超载预警：继续顺路接单将超出您的载具安全承重，已强行熔断！");
                }
            }
        }   

        // --- 以下为正常的抢单扣减逻辑 ---
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
        DispatchOrder order = orderService.getById(orderId);
        if (order == null || order.getTargetLon() == null) throw new BusinessException("坐标缺失");

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