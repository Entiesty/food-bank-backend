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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // 🚨 新增注入 Redis 操作模板
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public List<DispatchCandidateVO> smartMatchStations(DispatchOrder dispatchOrder) {
        log.info("📡 启动智能派单匹配，坐标:[{},{}], 需求大类:{}, 需求标签:{}",
                dispatchOrder.getTargetLon(), dispatchOrder.getTargetLat(),
                dispatchOrder.getRequiredCategory(), dispatchOrder.getRequiredTags());

        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults =
                stationService.searchNearbyStations(dispatchOrder.getTargetLon().doubleValue(), dispatchOrder.getTargetLat().doubleValue(), 5.0);

        if (geoResults == null || geoResults.getContent().isEmpty()) {
            return new ArrayList<>();
        }

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
                    .eq(Goods::getCategory, dispatchOrder.getRequiredCategory())
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

                        if (matchCount == 0) {
                            log.warn("⚠️ 属性熔断机制：需要 {}, 但物资 [{}] 仅有 {}, 已强行剥离丢弃！",
                                    reqTags, goods.getGoodsName(), goodsTags);
                            continue;
                        }
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
                        .station(station)
                        .goods(bestMatchedGoods)
                        .distance(path.distance())
                        .duration(path.duration())
                        .currentStock(bestMatchedGoods.getStock())
                        .build());
            } catch (Exception e) {
                log.error("高德路径规划异常，据点ID: {}", stationId, e);
            }
        }

        if (candidates.isEmpty()) return new ArrayList<>();
        return dispatchStrategy.calculateAndRank(candidates, dispatchOrder.getUrgencyLevel());
    }

    @Transactional(rollbackFor = Exception.class)
    public void grabOrder(Long orderId, Long volunteerId) {
        if (orderId == null || volunteerId == null) {
            throw new BusinessException("订单ID或志愿者ID不能为空");
        }

        User volunteer = userService.getById(volunteerId);
        if (volunteer == null) {
            throw new BusinessException("志愿者身份异常，请重新登录");
        }
        if (volunteer.getIsVerified() == null || volunteer.getIsVerified() == 0) {
            throw new BusinessException("您的志愿者资质尚未通过指挥中心审核，暂无接单权限！");
        }

        log.info("志愿者 [{}] 正在尝试抢夺订单 [{}]", volunteerId, orderId);

        boolean isGrabbed = orderService.update(
                new LambdaUpdateWrapper<DispatchOrder>()
                        .eq(DispatchOrder::getOrderId, orderId)
                        .eq(DispatchOrder::getStatus, 0)
                        .set(DispatchOrder::getStatus, 1)
        );

        if (!isGrabbed) {
            log.warn("抢单失败：订单 [{}] 状态已变更或不存在，竞争者 [{}]", orderId, volunteerId);
            throw new BusinessException("晚了一小步，该任务已有志愿者领取了。感谢你的热心，去看看其他任务吧！");
        }

        try {
            DeliveryTask deliveryTask = new DeliveryTask();
            deliveryTask.setOrderId(orderId);
            deliveryTask.setVolunteerId(volunteerId);
            deliveryTask.setTaskStatus((byte) 1);
            deliveryTask.setVersion(0);

            taskService.save(deliveryTask);
            log.info("抢单成功！已为订单 [{}] 生成执行任务，负责人: [{}]", orderId, volunteerId);

        } catch (Exception e) {
            log.error("插入任务表异常，触发唯一键回滚，订单号: {}", orderId, e);
            throw new BusinessException("系统繁忙，生成派送任务失败，请重试");
        }
    }

    public void pickUpGoods(Long taskId) {
        if (taskId == null) {
            throw new BusinessException("任务ID不能为空");
        }

        DeliveryTask deliveryTask = taskService.getById(taskId);
        if (deliveryTask == null) {
            throw new BusinessException("找不到对应的派送任务");
        }
        if (deliveryTask.getTaskStatus() != 1) {
            throw new BusinessException("当前任务状态不支持取货操作，请勿重复点击");
        }

        deliveryTask.setTaskStatus((byte) 2);

        boolean success = taskService.updateById(deliveryTask);
        if (!success) {
            log.warn("乐观锁拦截：任务 [{}] 状态已被修改，拦截重复操作", taskId);
            throw new BusinessException("操作冲突，请刷新页面获取最新状态");
        }
        log.info("任务 [{}] 状态已更新为：已取货", taskId);
    }

    /**
     * 🚨 核心逻辑：触发周边商铺紧急定向募捐 (加入 Redis 消息推送引擎)
     */
    public Map<String, Object> triggerEmergencyBroadcast(Long orderId) {
        DispatchOrder order = orderService.getById(orderId);
        if (order == null || order.getTargetLon() == null) {
            throw new BusinessException("坐标缺失，无法划定广播范围");
        }

        List<User> allMerchants = userService.list(new LambdaQueryWrapper<User>()
                .eq(User::getRole, 2)
                .isNotNull(User::getCurrentLon));

        List<User> list3km = new ArrayList<>();
        List<User> list10km = new ArrayList<>();

        // 1. 空间测距
        for (User merchant : allMerchants) {
            double distanceKm = calculateDistance(
                    order.getTargetLat().doubleValue(), order.getTargetLon().doubleValue(),
                    merchant.getCurrentLat().doubleValue(), merchant.getCurrentLon().doubleValue()
            );
            if (distanceKm <= 3.0) list3km.add(merchant);
            if (distanceKm <= 10.0) list10km.add(merchant);
        }

        List<User> targetMerchants;
        Map<String, Object> result = new HashMap<>();

        // 2. 降级网关
        if (!list3km.isEmpty()) {
            targetMerchants = list3km;
            result.put("radius", 3);
            result.put("isDegraded", false);
        } else if (!list10km.isEmpty()) {
            targetMerchants = list10km;
            result.put("radius", 10);
            result.put("isDegraded", true);
        } else {
            throw new BusinessException("终极熔断：扩大至全城 10 公里均无商铺响应！");
        }

        // 3. 🚀 THE MAGIC: 将紧急消息推入 Redis (60秒过期)，等待商家轮询提取
        for (User m : targetMerchants) {
            String redisKey = "EMERGENCY_BCAST:" + m.getUserId();
            // 组装消息：分类|订单ID
            String msgPayload = order.getRequiredCategory() + "|" + order.getOrderId();
            stringRedisTemplate.opsForValue().set(redisKey, msgPayload, 60, TimeUnit.SECONDS);
        }

        result.put("notifiedCount", targetMerchants.size());
        return result;
    }

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
}