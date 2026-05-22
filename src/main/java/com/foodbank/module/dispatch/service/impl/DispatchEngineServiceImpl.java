package com.foodbank.module.dispatch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.config.RabbitMQConfig;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
    @Autowired
    private DispatchOrderMapper dispatchOrderMapper;
    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private com.foodbank.module.system.config.service.IConfigService configService;

    // Redisson 分布式锁 + RabbitMQ 异步队列，用于抢单并发控制与削峰
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    public String getCurrentSystemMode() {
        return configService.getCurrentConfig().getSysMode();
    }

    public List<DispatchCandidateVO> smartMatchStations(DispatchOrder dispatchOrder) {
        log.info("[LBS匹配] 启动智能派单 坐标:[{},{}] 需求大类:{} 需求标签:{}",
                dispatchOrder.getTargetLon(), dispatchOrder.getTargetLat(),
                dispatchOrder.getRequiredCategory(), dispatchOrder.getRequiredTags());

        String sysMode = configService.getCurrentConfig().getSysMode();
        log.info("[LBS匹配] 当前系统模式: {}", sysMode);

        // 1. 领域大类映射 (Domain Mapping) — 通过 CategoryHierarchy 枚举统一管理
        List<String> targetCategories = com.foodbank.module.resource.goods.model.CategoryHierarchy.expand(dispatchOrder.getRequiredCategory());

        // 解析并清洗 JSON 格式的标签
        List<String> reqTags = new ArrayList<>();
        if (dispatchOrder.getRequiredTags() != null && !dispatchOrder.getRequiredTags().isEmpty()) {
            reqTags = Arrays.asList(dispatchOrder.getRequiredTags().replaceAll("[\"\\[\\]\\s]", "").split(","));
        }
        boolean hasTagRequirement = !reqTags.isEmpty();
        String originLonLat = dispatchOrder.getTargetLon() + "," + dispatchOrder.getTargetLat();
        List<DispatchCandidateVO> candidates = new ArrayList<>();

        // ==========================================================
        // L0: P2P 点对点直达匹配 — 商家直供物资跳过驿站中转
        // ==========================================================
        List<DispatchOrder> directSupplyOrders = dispatchOrderMapper.selectPendingDirectSupplyOrders(targetCategories, sysMode);

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
                if (path.distance() > 50000) continue; // 超出50km直接舍弃

                // 将商家映射为虚拟站点 (负ID)，复用现有骑士履约闭环而不修改表结构
                Station fakeStation = new Station();
                fakeStation.setStationId(-merchant.getUserId());
                fakeStation.setStationName(merchant.getUsername() + “ (P2P直达)”);
                fakeStation.setLongitude(merchant.getCurrentLon());
                fakeStation.setLatitude(merchant.getCurrentLat());
                fakeStation.setAddress("点对点直达配送");

                candidates.add(DispatchCandidateVO.builder()
                        .station(fakeStation).goods(goods)
                        .distance(path.distance()).duration(path.duration())
                        .currentStock(goods.getStock()).build());
            } catch (Exception e) {
                log.error("L0路线规划失败", e);
            }
        }

        if (!candidates.isEmpty()) {
            log.info("[L0 P2P] 发现 {} 个直供物资源，跳过驿站中转直接匹配", candidates.size());
            enrichRiderDistance(candidates);
            return dispatchStrategy.calculateAndRank(candidates, dispatchOrder.getUrgencyLevel());
        }

        // ==========================================================
        // L1: Hub-and-Spoke 驿站中转匹配 — Redis GEO 近邻检索 + 高德骑行测距
        // ==========================================================
        log.info("[L1 Hub中转] L0无匹配结果，降级进入驿站中转寻源");
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults =
                stationService.searchNearbyStations(dispatchOrder.getTargetLon().doubleValue(), dispatchOrder.getTargetLat().doubleValue(), 50.0);

        if (geoResults != null && !geoResults.getContent().isEmpty()) {
            for (var result : geoResults.getContent()) {
                Long stationId = Long.parseLong(result.getContent().getName());

                List<Goods> stationGoodsList = goodsMapper.selectAvailableGoodsByStation(stationId, targetCategories, sysMode);
                if (stationGoodsList.isEmpty()) continue;
                Goods bestMatchedGoods = stationGoodsList.get(0);
                double maxTagScore = 0.0;

                for (Goods goods : stationGoodsList) {
                    double currentTagScore = calculateTagScore(hasTagRequirement, reqTags, goods.getTags());
                    if (currentTagScore > maxTagScore) {
                        maxTagScore = currentTagScore;
                        bestMatchedGoods = goods;
                    }
                }
                // 品类已匹配, 标签匹配为加分项, 不因标签不匹配而丢弃结果
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
        enrichRiderDistance(candidates);
        return dispatchStrategy.calculateAndRank(candidates, dispatchOrder.getUrgencyLevel());
    }

    /**
     * 注入骑手到取货点的真实骑行距离/耗时，让前端展示总行程而非仅配送段
     */
    private void enrichRiderDistance(List<DispatchCandidateVO> candidates) {
        Long userId = UserContext.getUserId();
        if (userId == null) return;
        User rider = userService.getById(userId);
        if (rider == null || rider.getCurrentLon() == null || rider.getCurrentLat() == null) return;
        if (rider.getRole() == null || rider.getRole() != 3) return;

        String riderOrigin = rider.getCurrentLon() + "," + rider.getCurrentLat();
        for (DispatchCandidateVO c : candidates) {
            Station s = c.getStation();
            if (s == null || s.getLongitude() == null || s.getLatitude() == null) continue;
            String pickupDest = s.getLongitude() + "," + s.getLatitude();
            try {
                AmapDirectionResponse.Path path = amapClientService.getRidingDistance(riderOrigin, pickupDest);
                c.setRiderDistance(path.distance());
                c.setRiderDuration(path.duration());
            } catch (Exception e) {
                log.error("骑手到取货点路线规划失败 stationId={}", s.getStationId(), e);
            }
        }
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

    /**
     * 高并发抢单：Redisson 分布式锁 (tryLock 2s/10s TTL) 保护临界区，
     * 锁内执行 CVRP 载具容量与跨区距离校验，通过后投递 RabbitMQ 异步落库。
     */
    public void grabOrder(Long orderId, Long volunteerId) {
        if (orderId == null || volunteerId == null) throw new BusinessException("订单ID或志愿者ID不能为空");

        User volunteer = userService.getById(volunteerId);
        if (volunteer == null) throw new BusinessException("志愿者身份异常，请重新登录");
        if (volunteer.getIsVerified() == null || volunteer.getIsVerified() == 0) {
            throw new BusinessException("您的资质尚未通过审核，暂无接单权限！");
        }

        // 1. Redisson 分布式排他锁，防止并发抢单惊群效应
        String lockKey = "lock:order:grab:" + orderId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 2. tryLock(2, 10, TimeUnit.SECONDS): 等待2秒，锁持有10秒自动释放
            boolean isLocked = lock.tryLock(2, 10, TimeUnit.SECONDS);
            if (!isLocked) {
                throw new BusinessException("当前抢单人数过多或已被他人抢占，请稍后重试！");
            }

            DispatchOrder order = orderService.getById(orderId);
            if (order == null || order.getStatus() != 0) {
                throw new BusinessException("该订单已被领取或状态已变更");
            }

            // 3. CVRP 载具容量约束校验: 跨区距离 + 累计装载重量/体积上限
            Integer vType = volunteer.getVehicleType() != null ? volunteer.getVehicleType() : 1;

            // 3.1 跨区距离拦截
            if (order.getTargetLat() != null && order.getTargetLon() != null
                    && order.getSourceLat() != null && order.getSourceLon() != null) {
                double dist = calculateDistance(order.getSourceLat().doubleValue(), order.getSourceLon().doubleValue(),
                        order.getTargetLat().doubleValue(), order.getTargetLon().doubleValue());
                if (dist > 50.0 && vType <= 2) {
                    throw new BusinessException("运力不匹配：该单跨区 " + String.format("%.1f", dist) + "km，超出当前载具配送范围");
                }
            }

            // 3.2 累计装载算力预警
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
                        throw new BusinessException("载具容量已达上限，请先完成当前配送");
                    }
                    if ((currentWeightPoints + newWeightPoint) > maxWeightPoints) {
                        throw new BusinessException("超出载具承重上限，请先完成当前配送");
                    }
                }
            }

            // 4. 通过 RabbitMQ 异步落库，削峰填谷
            Map<String, Object> message = new HashMap<>();
            message.put("orderId", orderId);
            message.put("volunteerId", volunteerId);
            message.put("timestamp", System.currentTimeMillis());

            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.GRAB_ORDER_ROUTING_KEY, message);

            log.info("[抢单] 骑士[{}] 通过CVRP校验 单号{} 已投递MQ", volunteerId, order.getOrderSn());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("排队超时，请重试");
        } finally {
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public void pickUpGoods(Long taskId) {
        DeliveryTask deliveryTask = taskService.getById(taskId);
        if (deliveryTask == null || deliveryTask.getTaskStatus() != 1) throw new BusinessException("任务状态异常");
        deliveryTask.setTaskStatus((byte) 2);
        if (!taskService.updateById(deliveryTask)) throw new BusinessException("操作冲突，请重试");
    }

    public Map<String, Object> triggerEmergencyBroadcast(Long orderId) {
        // Redis setIfAbsent 防重放锁 (30s TTL)，防止管理员误触发重复广播
        String lockKey = "LOCK:BROADCAST:" + orderId;
        Boolean isLocked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 30, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(isLocked)) {
            throw new BusinessException("广播信号发送中，请30秒后再试");
        }

        DispatchOrder order = orderService.getById(orderId);
        if (order == null || order.getTargetLon() == null) throw new BusinessException("坐标缺失");

        List<User> allMerchants = userService.list(new LambdaQueryWrapper<User>().eq(User::getRole, 2).isNotNull(User::getCurrentLon));
        List<User> list3km = new ArrayList<>(), list10km = new ArrayList<>();

        for (User m : allMerchants) {
            double dist = calculateDistance(order.getTargetLat().doubleValue(), order.getTargetLon().doubleValue(),
                    m.getCurrentLat().doubleValue(), m.getCurrentLon().doubleValue());
            if (dist <= 10.0) list3km.add(m);
            if (dist <= 30.0) list10km.add(m);
        }

        List<User> targetMerchants;
        Map<String, Object> result = new HashMap<>();

        if (!list3km.isEmpty()) {
            targetMerchants = list3km; result.put("radius", 3); result.put("isDegraded", false);
        } else if (!list10km.isEmpty()) {
            targetMerchants = list10km; result.put("radius", 10); result.put("isDegraded", true);
        } else if (!allMerchants.isEmpty()) {
            // 全城广播兜底：30km内无商铺时覆盖全城
            targetMerchants = allMerchants; result.put("radius", -1); result.put("isDegraded", true);
            log.warn("[紧急广播] 30km内无商铺响应，触发全城广播兜底 (覆盖{}家商铺)", allMerchants.size());
        } else {
            // 失败也需手动释放防抖锁，否则要等 30 秒
            stringRedisTemplate.delete(lockKey);
            throw new BusinessException("全城暂无可用商铺资源");
        }

        // 查求助人信息
        User recipient = userService.getById(order.getDestId());
        String recipientName = recipient != null ? recipient.getUsername() : "未知";
        String recipientTag = recipient != null && recipient.getUserTag() != null ? recipient.getUserTag() : "";
        String doorNumber = recipient != null && recipient.getDoorNumber() != null ? recipient.getDoorNumber() : "";
        String urgency = String.valueOf(order.getUrgencyLevel() != null ? order.getUrgencyLevel() : 1);
        String recipientLon = recipient != null && recipient.getCurrentLon() != null ? recipient.getCurrentLon().toString() : "";
        String recipientLat = recipient != null && recipient.getCurrentLat() != null ? recipient.getCurrentLat().toString() : "";

        for (User m : targetMerchants) {
            // 持久化广播: 按 userId + orderId 分键, 支持多订单共存, TTL 2 小时
            String redisKey = "EMERGENCY_BCAST:" + m.getUserId() + ":" + orderId;
            String msg = order.getRequiredCategory() + "|" + order.getOrderId() + "|"
                       + recipientName + "|" + recipientTag + "|" + doorNumber + "|" + urgency + "|"
                       + recipientLon + "|" + recipientLat;
            log.info("[紧急广播] 写入Redis key={} msg={}", redisKey, msg);
            stringRedisTemplate.opsForValue().set(redisKey, msg, 2, TimeUnit.HOURS);
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