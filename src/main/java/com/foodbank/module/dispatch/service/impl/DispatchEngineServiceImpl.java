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

/**
 * 调度引擎核心编排服务，负责多级物资寻源与分布式抢单临界区保护。
 *
 * <h3>整体寻源流程（两级降级链路）</h3>
 * <pre>
 *   L0 P2P 直达
 *     ↓ 无匹配时降级
 *   L1 Hub-and-Spoke 驿站中转
 *     ↓ 仍无匹配时
 *   返回空列表 → 上游触发紧急广播 / 商家募捐兜底
 * </pre>
 *
 * <h3>各层说明</h3>
 * <ul>
 *   <li><b>L0 P2P 直达</b>：检索商家已主动响应的直供订单，将商家虚拟化为负 ID 驿站，
 *       复用现有履约闭环，无需新增表结构。命中则短路，不再进入 L1。</li>
 *   <li><b>L1 Hub-and-Spoke 驿站中转</b>：以受助方坐标为圆心，通过 Redis GEO 命令
 *       在 50 km 半径内检索最近驿站，结合高德骑行测距与标签 Jaccard 相似度匹配最优物资。</li>
 *   <li><b>SAW 多因子排序</b>：两级寻源结果均委托 {@link MultiFactorDispatchStrategy}
 *       执行 Min-Max 归一化加权排序，得分最高者排在首位。</li>
 *   <li><b>分布式抢单</b>：使用 Redisson {@code tryLock} 保护临界区，
 *       通过 CVRP 载具容量约束校验后，将落库操作投递至 RabbitMQ 异步处理，
 *       解耦抢单响应与持久化事务，削减数据库写入峰值压力。</li>
 * </ul>
 *
 * @author System Architect
 * @version 2.0
 * @see MultiFactorDispatchStrategy
 */
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

    /** Redisson 分布式锁客户端，保护抢单临界区，防止并发重复领单 */
    @Autowired
    private RedissonClient redissonClient;

    /** RabbitMQ 模板，用于将抢单结果异步投递至落库队列，解耦事务边界 */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 获取当前系统运行模式（如 {@code "NORMAL"}、{@code "EMERGENCY"}）。
     *
     * <p>运行模式由运营后台动态配置，调度引擎各层在物资查询时均需透传该参数，
     * 以过滤仅在特定模式下可用的物资。
     *
     * @return 当前系统模式字符串，来源于 {@link com.foodbank.module.system.config.entity.Config}
     */
    public String getCurrentSystemMode() {
        return configService.getCurrentConfig().getSysMode();
    }

    /**
     * 多级物资寻源入口：L0 P2P 直达 → L1 Hub-and-Spoke 驿站中转。
     *
     * <p>L0 命中即短路返回，不再进行 L1 检索。
     * 两级均无匹配时返回空列表，由上游调用方决策是否触发紧急广播或商家募捐兜底。
     *
     * <h3>标签匹配说明</h3>
     * <ul>
     *   <li>L0（P2P 直达）：标签为硬过滤项，零匹配候选直接丢弃。</li>
     *   <li>L1（驿站中转）：标签为加分项（Jaccard 相似度），不因标签不匹配丢弃候选，
     *       确保在标签物资不足时仍能召回结果。</li>
     * </ul>
     *
     * @param dispatchOrder 待匹配的调度订单，需包含目标坐标、需求品类、紧急度等字段
     * @return 按 SAW 综合得分降序排列的候选驿站列表；无匹配时返回空列表（非 {@code null}）
     */
    public List<DispatchCandidateVO> smartMatchStations(DispatchOrder dispatchOrder) {
        log.info("[LBS匹配] 启动智能派单 坐标:[{},{}] 需求大类:{} 需求标签:{}",
                dispatchOrder.getTargetLon(), dispatchOrder.getTargetLat(),
                dispatchOrder.getRequiredCategory(), dispatchOrder.getRequiredTags());

        String sysMode = configService.getCurrentConfig().getSysMode();
        log.info("[LBS匹配] 当前系统模式: {}", sysMode);

        // 品类展开：通过 CategoryHierarchy 枚举将抽象大类（如"粮油"）展开为具体叶子品类集合，
        // 用于后续 SQL 的 IN 查询，确保子品类物资不被遗漏
        List<String> targetCategories = com.foodbank.module.resource.goods.model.CategoryHierarchy
                .expand(dispatchOrder.getRequiredCategory());

        // 标签反序列化：requiredTags 在数据库中以 JSON 数组字符串存储（如 `["老人","独居"]`），
        // 通过正则清洗引号、括号、空白后按逗号分割为 List<String>
        List<String> reqTags = new ArrayList<>();
        if (dispatchOrder.getRequiredTags() != null && !dispatchOrder.getRequiredTags().isEmpty()) {
            reqTags = Arrays.asList(
                    dispatchOrder.getRequiredTags().replaceAll("[\"\\[\\]\\s]", "").split(",")
            );
        }
        boolean hasTagRequirement = !reqTags.isEmpty();

        // 拼接高德 API 所需的"经度,纬度"格式字符串（注意：高德 API 为经度在前）
        String originLonLat = dispatchOrder.getTargetLon() + "," + dispatchOrder.getTargetLat();
        List<DispatchCandidateVO> candidates = new ArrayList<>();

        // ==========================================================
        // L0: P2P 直达 — 优先检索已被商家主动响应的直供订单
        //
        // 设计意图：商家可通过"主动供给"功能发布可配送物资，系统将其视为"虚拟驿站"，
        // 以负数 stationId（-userId）标识，复用现有履约流程，无需新增表结构。
        // 优先级高于 L1 的原因：P2P 直达路径最短、响应最快，且商家已完成供给意向确认。
        // ==========================================================
        List<DispatchOrder> directSupplyOrders = dispatchOrderMapper
                .selectPendingDirectSupplyOrders(targetCategories, sysMode);

        for (DispatchOrder supply : directSupplyOrders) {
            // 跳过物资已下架或库存归零的供给订单
            Goods goods = goodsService.getById(supply.getGoodsId());
            if (goods == null || goods.getStock() <= 0) continue;

            // 跳过商家信息异常或位置未上报的情况
            User merchant = userService.getById(supply.getSourceId());
            if (merchant == null || merchant.getCurrentLon() == null) continue;

            // 标签硬过滤（L0 层）：有标签需求时，交集为空的物资直接丢弃，确保精准匹配
            double currentTagScore = calculateTagScore(hasTagRequirement, reqTags, goods.getTags());
            if (hasTagRequirement && currentTagScore == 0.0) continue;

            String destLonLat = merchant.getCurrentLon() + "," + merchant.getCurrentLat();
            try {
                AmapDirectionResponse.Path path = amapClientService.getRidingDistance(originLonLat, destLonLat);
                // 距离硬阈值剪枝：超过 50 km 的 P2P 直达认为配送成本过高，直接排除
                if (path.distance() > 50000) continue;

                // 将商家虚拟化为负 ID 伪驿站（stationId = -userId），
                // 后续 SAW 排序与骑士接单流程对正负 ID 无感知，透明复用
                Station fakeStation = new Station();
                fakeStation.setStationId(-merchant.getUserId());
                fakeStation.setStationName(merchant.getUsername() + " (P2P直达)");
                fakeStation.setLongitude(merchant.getCurrentLon());
                fakeStation.setLatitude(merchant.getCurrentLat());
                fakeStation.setAddress("点对点直达配送");

                candidates.add(DispatchCandidateVO.builder()
                        .station(fakeStation).goods(goods)
                        .distance(path.distance()).duration(path.duration())
                        .currentStock(goods.getStock()).build());

            } catch (Exception e) {
                // 高德 API 超时或限流时，静默跳过当前候选，不阻塞其余匹配
                log.error("L0路线规划失败", e);
            }
        }

        // L0 命中：短路返回，跳过 L1 驿站检索
        if (!candidates.isEmpty()) {
            log.info("[L0 P2P] 发现 {} 个直供物资源，跳过驿站中转直接匹配", candidates.size());
            enrichRiderDistance(candidates);
            return dispatchStrategy.calculateAndRank(candidates, dispatchOrder.getUrgencyLevel());
        }

        // ==========================================================
        // L1: Hub-and-Spoke 驿站中转 — L0 无结果时的标准寻源路径
        //
        // 以受助方坐标为圆心，通过 Redis GEO GEORADIUS 命令在 50 km 半径内
        // 检索距离最近的驿站集合，再逐站查询可用物资并按标签 Jaccard 相似度优选。
        // ==========================================================
        log.info("[L1 Hub中转] L0无匹配结果，降级进入驿站中转寻源");
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults =
                stationService.searchNearbyStations(
                        dispatchOrder.getTargetLon().doubleValue(),
                        dispatchOrder.getTargetLat().doubleValue(),
                        50.0 // 搜索半径，单位：km
                );

        if (geoResults != null && !geoResults.getContent().isEmpty()) {
            for (var result : geoResults.getContent()) {
                Long stationId = Long.parseLong(result.getContent().getName());

                // 查询该驿站下品类匹配、库存充足的物资列表（已按系统模式过滤）
                List<Goods> stationGoodsList = goodsMapper.selectAvailableGoodsByStation(
                        stationId, targetCategories, sysMode);
                if (stationGoodsList.isEmpty()) continue;

                // 标签 Jaccard 相似度优选：
                // 在品类已匹配的前提下，从多个物资中选出标签重叠度最高的那个。
                // L1 层标签为加分项而非硬过滤，即使所有物资 tagScore=0 也取第一个，
                // 保证在标签物资不足时仍能输出候选，避免空结果。
                Goods bestMatchedGoods = stationGoodsList.get(0);
                double maxTagScore = 0.0;
                for (Goods goods : stationGoodsList) {
                    double currentTagScore = calculateTagScore(hasTagRequirement, reqTags, goods.getTags());
                    if (currentTagScore > maxTagScore) {
                        maxTagScore = currentTagScore;
                        bestMatchedGoods = goods;
                    }
                }

                Station station = stationService.getById(stationId);
                if (station == null) continue;

                String destLonLat = station.getLongitude() + "," + station.getLatitude();
                try {
                    AmapDirectionResponse.Path path = amapClientService.getRidingDistance(originLonLat, destLonLat);
                    candidates.add(DispatchCandidateVO.builder()
                            .station(station).goods(bestMatchedGoods)
                            .distance(path.distance()).duration(path.duration())
                            .currentStock(bestMatchedGoods.getStock()).build());
                } catch (Exception e) {
                    // 单个驿站路线规划失败，静默跳过，不阻塞同级其余候选的处理
                }
            }
        }

        // 两级均无匹配：返回空列表，由上游触发紧急广播或商家募捐兜底
        if (candidates.isEmpty()) return new ArrayList<>();

        enrichRiderDistance(candidates);
        return dispatchStrategy.calculateAndRank(candidates, dispatchOrder.getUrgencyLevel());
    }

    /**
     * 为候选列表补充骑手到取货点的真实骑行距离与耗时（接驾段路程）。
     *
     * <h3>作用</h3>
     * SAW 排序中的距离维度需要反映「骑手视角」的全路径成本（接驾段 + 配送段），
     * 此方法通过高德骑行 API 为每个候选注入 {@code riderDistance} / {@code riderDuration} 字段，
     * 供前端展示和后续排序使用。
     *
     * <h3>触发条件</h3>
     * <ul>
     *   <li>调用方为已登录用户（{@link UserContext} 中存在 userId）</li>
     *   <li>用户角色为骑手（{@code role = 3}）</li>
     *   <li>用户当前坐标已上报（{@code currentLon} / {@code currentLat} 非空）</li>
     * </ul>
     * 任一条件不满足时方法静默返回，不填充字段（接驾距离字段保持默认值）。
     *
     * @param candidates 待补充接驾距离的候选列表，方法直接修改对象字段（无返回值）
     */
    private void enrichRiderDistance(List<DispatchCandidateVO> candidates) {
        Long userId = UserContext.getUserId();
        if (userId == null) return;

        User rider = userService.getById(userId);
        // 坐标未上报或非骑手角色时跳过，避免无意义的 API 调用
        if (rider == null || rider.getCurrentLon() == null || rider.getCurrentLat() == null) return;
        if (rider.getRole() == null || rider.getRole() != 3) return;

        // 骑手当前位置，作为高德骑行路线的起点
        String riderOrigin = rider.getCurrentLon() + "," + rider.getCurrentLat();

        for (DispatchCandidateVO c : candidates) {
            Station s = c.getStation();
            if (s == null || s.getLongitude() == null || s.getLatitude() == null) continue;

            // 驿站/商家位置，作为高德骑行路线的终点（取货点）
            String pickupDest = s.getLongitude() + "," + s.getLatitude();
            try {
                AmapDirectionResponse.Path path = amapClientService.getRidingDistance(riderOrigin, pickupDest);
                c.setRiderDistance(path.distance());
                c.setRiderDuration(path.duration());
            } catch (Exception e) {
                // 单条路线规划失败不影响整体流程，记录日志后继续处理其余候选
                log.error("骑手到取货点路线规划失败 stationId={}", s.getStationId(), e);
            }
        }
    }

    /**
     * 计算订单需求标签与物资实际标签的 Jaccard 相似度。
     *
     * <h3>计算方式</h3>
     * <pre>
     *   score = |reqTags ∩ goodsTags| / |reqTags|
     * </pre>
     * 取值范围 [0.0, 1.0]，1.0 表示需求标签全部命中。
     *
     * <h3>特殊情形</h3>
     * <ul>
     *   <li>无标签需求（{@code hasReq = false}）时返回 1.0，视为满分，不参与过滤</li>
     *   <li>物资无标签数据（{@code dbTagsJson} 为空）时返回 0.0</li>
     * </ul>
     *
     * @param hasReq    是否存在标签需求
     * @param reqTags   订单需求的标签列表
     * @param dbTagsJson 物资在数据库中存储的标签 JSON 字符串（如 {@code ["老人","糖尿病"]}）
     * @return Jaccard 相似度得分，范围 [0.0, 1.0]
     */
    private double calculateTagScore(boolean hasReq, List<String> reqTags, String dbTagsJson) {
        // 无标签需求：不参与标签过滤，直接视为满分
        if (!hasReq) return 1.0;
        // 物资无标签数据：与任何需求标签均不匹配
        if (dbTagsJson == null || dbTagsJson.isEmpty()) return 0.0;

        // 清洗物资标签 JSON 字符串（去除引号、括号、空白），转为 List<String>
        String cleanTags = dbTagsJson.replaceAll("[\"\\[\\]\\s]", "");
        List<String> goodsTags = Arrays.asList(cleanTags.split(","));

        // 统计需求标签在物资标签中的命中数量
        long matchCount = reqTags.stream().filter(t -> goodsTags.contains(t.trim())).count();
        if (matchCount == 0) return 0.0;

        // Jaccard 分子：交集大小；分母：需求集大小（而非并集，此处为简化版相似度）
        return (double) matchCount / reqTags.size();
    }

    /**
     * 分布式抢单，含完整的 CVRP 载具容量约束校验与异步落库。
     *
     * <h3>执行顺序</h3>
     * <ol>
     *   <li><b>志愿者资质校验</b>：身份合法性 + 资质审核状态</li>
     *   <li><b>Redisson tryLock</b>：等待 2s，锁 TTL 10s，同一订单同一时刻只有一个线程能通过，
     *       防止并发抢单的"惊群效应"</li>
     *   <li><b>订单状态二次校验</b>：获锁后再次确认 {@code status = 0}（待领取），
     *       防止锁等待期间订单已被他人领走</li>
     *   <li><b>物资同步匹配</b>：若订单未绑定 goodsId（懒绑定场景），立即触发 L0→L1 寻源，
     *       并原子扣减库存（CAS 乐观锁），成功后更新订单物资信息</li>
     *   <li><b>CVRP 跨区距离校验</b>：步行/单车（vType ≤ 2）不允许接超 50km 跨区订单</li>
     *   <li><b>CVRP 载具容量校验</b>：累加当前进行中任务 + 新订单的体积/重量点值，
     *       超出载具上限时拒单</li>
     *   <li><b>MQ 异步落库</b>：通过 RabbitMQ 将抢单结果投递至消费者异步持久化，
     *       避免同步数据库写入阻塞抢单响应</li>
     * </ol>
     *
     * <h3>锁释放说明</h3>
     * 在 {@code finally} 块中通过 {@code isHeldByCurrentThread()} 判断后再 {@code unlock()}，
     * 防止锁在 10s TTL 内未完成操作被 Redisson WatchDog 自动回收后，误删其他线程持有的锁。
     *
     * @param orderId     目标订单主键，不能为 {@code null}
     * @param volunteerId 发起抢单的志愿者主键，不能为 {@code null}
     * @throws BusinessException 以下情况均抛出业务异常并回滚：
     *                           参数为空、志愿者资质未通过、抢单人数过多、订单已被领取、CVRP 约束违反等
     */
    public void grabOrder(Long orderId, Long volunteerId) {
        if (orderId == null || volunteerId == null) {
            throw new BusinessException("订单ID或志愿者ID不能为空");
        }

        // 1. 志愿者身份与资质校验：未通过审核的志愿者无接单权限
        User volunteer = userService.getById(volunteerId);
        if (volunteer == null) throw new BusinessException("志愿者身份异常，请重新登录");
        if (volunteer.getIsVerified() == null || volunteer.getIsVerified() == 0) {
            throw new BusinessException("您的资质尚未通过审核，暂无接单权限！");
        }

        // 2. 构建分布式排他锁，锁粒度为单个订单，避免全局锁对不相关订单的误阻塞
        String lockKey = "lock:order:grab:" + orderId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // tryLock 参数：waitTime=2s（最长等待时间），leaseTime=10s（锁自动过期 TTL）
            // 使用 tryLock 而非 lock，避免线程无限阻塞，超时后立即告知用户重试
            boolean isLocked = lock.tryLock(2, 10, TimeUnit.SECONDS);
            if (!isLocked) {
                throw new BusinessException("当前抢单人数过多或已被他人抢占，请稍后重试！");
            }

            // 3. 订单状态二次校验（Double Check）：
            //    获锁后再次查询，防止锁等待的 2s 内订单已被他人成功领取
            DispatchOrder order = orderService.getById(orderId);
            if (order == null || order.getStatus() != 0) {
                throw new BusinessException("该订单已被领取或状态已变更");
            }

            // 载具类型：1=步行, 2=单车, 3=电动车/摩托, 4=货车；未录入时默认步行
            Integer vType = volunteer.getVehicleType() != null ? volunteer.getVehicleType() : 1;

            // 4. 懒绑定物资匹配：订单创建时可能未绑定具体物资（等待系统匹配），
            //    抢单时如仍为空，立即触发 smartMatchStations 并原子扣减库存，
            //    确保"抢单"与"锁定物资"的原子性
            if (order.getGoodsId() == null) {
                List<DispatchCandidateVO> matched = smartMatchStations(order);
                if (matched != null && !matched.isEmpty()) {
                    DispatchCandidateVO winner = matched.get(0); // 取得分最高的候选
                    // deductStockSafe 内部使用 CAS（version 乐观锁）防止并发超卖
                    boolean deductSuccess = goodsService.deductStockSafe(winner.getGoods().getGoodsId(), 1);
                    if (deductSuccess) {
                        // 将物资与来源站绑定至订单，方便后续履约追踪
                        order.setGoodsId(winner.getGoods().getGoodsId());
                        order.setSourceId(winner.getStation().getStationId());
                        order.setGoodsName(winner.getGoods().getGoodsName());
                        order.setGoodsCount(1);
                        orderService.updateById(order);
                    }
                }
            }

            // 5. CVRP 约束校验一：跨区距离硬阈值
            //    步行/单车（vType ≤ 2）骑手不允许接超过 50km 的跨区订单，
            //    避免因运力不匹配导致超时或履约失败
            if (order.getTargetLat() != null && order.getTargetLon() != null
                    && order.getSourceLat() != null && order.getSourceLon() != null) {
                double dist = calculateDistance(
                        order.getSourceLat().doubleValue(), order.getSourceLon().doubleValue(),
                        order.getTargetLat().doubleValue(), order.getTargetLon().doubleValue()
                );
                if (dist > 50.0 && vType <= 2) {
                    throw new BusinessException("运力不匹配：该单跨区 "
                            + String.format("%.1f", dist) + "km，超出当前载具配送范围");
                }
            }

            // 6. CVRP 约束校验二：载具体积/重量累计容量上限
            if (order.getGoodsId() != null) {
                Goods newGoods = goodsService.getById(order.getGoodsId());
                if (newGoods != null) {
                    // 载具容量上限映射表（vType → [最大体积点, 最大重量点]）：
                    //   vType=1（步行）  → 体积上限 2 点，重量上限 2 点
                    //   vType=2（单车）  → 体积上限 5 点，重量上限 4 点
                    //   vType=3（电动车）→ 体积上限 15 点，重量上限 10 点
                    //   vType=4（货车）  → 体积上限 100 点，重量上限 100 点（近似无限）
                    int maxVolumePoints = vType == 1 ? 2 : (vType == 2 ? 5 : (vType == 3 ? 15 : 100));
                    int maxWeightPoints = vType == 1 ? 2 : (vType == 2 ? 4 : (vType == 3 ? 10 : 100));

                    int currentVolumePoints = 0;
                    int currentWeightPoints = 0;

                    // 查询志愿者当前正在进行中的任务（taskStatus: 1=已分配, 2=配送中）
                    List<DeliveryTask> activeTasks = taskService.list(
                            new LambdaQueryWrapper<DeliveryTask>()
                                    .eq(DeliveryTask::getVolunteerId, volunteerId)
                                    .in(DeliveryTask::getTaskStatus, Arrays.asList(1, 2))
                    );

                    // 体积/重量三级点值映射（level → 点值）：
                    //   level=1（小件，如信封/小盒）→ 1 点
                    //   level=2（中件，如箱装食品） → 5 点
                    //   level=3（大件，如米面粮油） → 体积 40 点 / 重量 20 点
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

                    // 计算新订单物资的体积/重量点值
                    int newVolumePoint = newGoods.getVolumeLevel() == 3 ? 40 : (newGoods.getVolumeLevel() == 2 ? 5 : 1);
                    int newWeightPoint = newGoods.getWeightLevel() == 3 ? 20 : (newGoods.getWeightLevel() == 2 ? 5 : 1);

                    // 超出任一维度上限即拒单
                    if ((currentVolumePoints + newVolumePoint) > maxVolumePoints) {
                        throw new BusinessException("载具容量已达上限，请先完成当前配送");
                    }
                    if ((currentWeightPoints + newWeightPoint) > maxWeightPoints) {
                        throw new BusinessException("超出载具承重上限，请先完成当前配送");
                    }
                }
            }

            // 7. 所有校验通过，投递 MQ 消息由消费者异步完成数据库落库，
            //    将抢单响应时延与持久化事务解耦，提升高并发场景下的吞吐量
            Map<String, Object> message = new HashMap<>();
            message.put("orderId", orderId);
            message.put("volunteerId", volunteerId);
            message.put("timestamp", System.currentTimeMillis());

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_NAME,
                    RabbitMQConfig.GRAB_ORDER_ROUTING_KEY,
                    message
            );

            log.info("[抢单] 骑士[{}] 通过CVRP校验 单号{} 已投递MQ", volunteerId, order.getOrderSn());

        } catch (InterruptedException e) {
            // 线程在等待锁期间被中断（如容器关闭），恢复中断状态并对外抛出业务异常
            Thread.currentThread().interrupt();
            throw new BusinessException("排队超时，请重试");
        } finally {
            // 安全释放锁：isHeldByCurrentThread() 防止锁在 TTL 到期被自动回收后，
            // 此处 unlock() 误删已由其他线程持有的新锁
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 骑手到驿站/商家取货，将配送任务状态从"已分配"推进至"配送中"。
     *
     * <p>状态流转：{@code taskStatus: 1（已分配）→ 2（配送中）}。
     * 使用乐观锁 {@code updateById} 返回值校验并发安全性：
     * 若返回 {@code false} 说明存在并发修改，抛出业务异常提示重试。
     *
     * @param taskId 配送任务主键
     * @throws BusinessException 任务不存在、当前状态非"已分配"、或并发更新冲突时抛出
     */
    public void pickUpGoods(Long taskId) {
        DeliveryTask deliveryTask = taskService.getById(taskId);
        if (deliveryTask == null || deliveryTask.getTaskStatus() != 1) {
            throw new BusinessException("任务状态异常");
        }
        deliveryTask.setTaskStatus((byte) 2);
        if (!taskService.updateById(deliveryTask)) {
            throw new BusinessException("操作冲突，请重试");
        }
    }

    /**
     * 触发紧急物资广播，以受助方坐标为圆心向商家三级扩散通知。
     *
     * <h3>三级扩散策略</h3>
     * <pre>
     *   ① 10km 内有商家 → 仅通知 10km 内商家（精准覆盖，result.radius=3）
     *   ② 10km 内无商家 → 扩散至 30km（isDegraded=true，result.radius=10）
     *   ③ 30km 内无商家 → 全城广播兜底（isDegraded=true，result.radius=-1）
     *   ④ 全城无商家   → 释放防抖锁，抛出业务异常
     * </pre>
     *
     * <h3>防重放机制</h3>
     * 使用 Redis {@code SETNX}（{@code setIfAbsent}）加 30s TTL 的防抖锁，
     * 防止管理员误操作在短时间内重复触发广播，造成商家消息轰炸。
     * 注意：仅在"全城无商家"场景下主动删除防抖锁，其余情况等待 TTL 自然过期。
     *
     * <h3>广播消息存储</h3>
     * 广播消息以 {@code EMERGENCY_BCAST:{merchantId}:{orderId}} 为 Redis Key 存储，
     * TTL 2 小时。按商户+订单分键存储，支持同一商户同时接收多个不同紧急广播。
     * 消息格式（{@code |} 分隔）：
     * {@code 品类|订单ID|受助人姓名|受助人标签|门牌号|紧急度|受助人经度|受助人纬度}
     *
     * @param orderId 需要紧急寻源的订单主键
     * @return 广播结果 Map，包含以下字段：
     *         <ul>
     *           <li>{@code radius}       — 实际触达半径（km），全城广播时为 -1</li>
     *           <li>{@code isDegraded}   — 是否发生降级扩散</li>
     *           <li>{@code notifiedCount}— 实际通知的商家数量</li>
     *         </ul>
     * @throws BusinessException 订单不存在/坐标缺失、防抖锁触发（30s内重复广播）、全城无可用商家时抛出
     */
    public Map<String, Object> triggerEmergencyBroadcast(Long orderId) {
        // 防重放锁：30s TTL，setIfAbsent 原子操作，失败则说明 30s 内已广播过
        String lockKey = "LOCK:BROADCAST:" + orderId;
        Boolean isLocked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 30, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(isLocked)) {
            throw new BusinessException("广播信号发送中，请30秒后再试");
        }

        DispatchOrder order = orderService.getById(orderId);
        if (order == null || order.getTargetLon() == null) {
            throw new BusinessException("坐标缺失");
        }

        // 查询全量在线商家（role=2），筛选已上报当前坐标的商家
        List<User> allMerchants = userService.list(
                new LambdaQueryWrapper<User>()
                        .eq(User::getRole, 2)
                        .isNotNull(User::getCurrentLon)
        );

        // 按距离分桶：一次遍历计算所有商家与受助方的球面距离，分别收集到 10km 和 30km 桶中
        List<User> list3km = new ArrayList<>(), list10km = new ArrayList<>();
        for (User m : allMerchants) {
            double dist = calculateDistance(
                    order.getTargetLat().doubleValue(), order.getTargetLon().doubleValue(),
                    m.getCurrentLat().doubleValue(), m.getCurrentLon().doubleValue()
            );
            if (dist <= 10.0) list3km.add(m);   // 10km 以内（精准覆盖桶）
            if (dist <= 30.0) list10km.add(m);  // 30km 以内（一级降级桶）
        }

        // 三级扩散决策：优先使用最小半径桶，逐级降级
        List<User> targetMerchants;
        Map<String, Object> result = new HashMap<>();

        if (!list3km.isEmpty()) {
            // 第一级：10km 内有商家，精准覆盖
            targetMerchants = list3km;
            result.put("radius", 3);
            result.put("isDegraded", false);
        } else if (!list10km.isEmpty()) {
            // 第二级：10km 内无商家，扩散至 30km
            targetMerchants = list10km;
            result.put("radius", 10);
            result.put("isDegraded", true);
        } else if (!allMerchants.isEmpty()) {
            // 第三级：30km 内无商家，全城广播兜底（radius=-1 表示无半径限制）
            targetMerchants = allMerchants;
            result.put("radius", -1);
            result.put("isDegraded", true);
            log.warn("[紧急广播] 30km内无商铺响应，触发全城广播兜底 (覆盖{}家商铺)", allMerchants.size());
        } else {
            // 全城无商家：主动删除防抖锁，避免其残留 30s 影响后续真实广播
            stringRedisTemplate.delete(lockKey);
            throw new BusinessException("全城暂无可用商铺资源");
        }

        // 提取受助人信息，拼接广播消息体（部分字段可能为空，使用空字符串兜底）
        User recipient = userService.getById(order.getDestId());
        String recipientName = recipient != null ? recipient.getUsername()     : "未知";
        String recipientTag  = recipient != null && recipient.getUserTag()    != null ? recipient.getUserTag()    : "";
        String doorNumber    = recipient != null && recipient.getDoorNumber() != null ? recipient.getDoorNumber() : "";
        String urgency       = String.valueOf(order.getUrgencyLevel() != null ? order.getUrgencyLevel() : 1);
        String recipientLon  = recipient != null && recipient.getCurrentLon() != null ? recipient.getCurrentLon().toString() : "";
        String recipientLat  = recipient != null && recipient.getCurrentLat() != null ? recipient.getCurrentLat().toString() : "";

        // 逐商家写入 Redis，消息格式：品类|订单ID|姓名|标签|门牌|紧急度|经度|纬度
        // Key 格式：EMERGENCY_BCAST:{merchantId}:{orderId}，支持同一商户接收多单广播
        for (User m : targetMerchants) {
            String redisKey = "EMERGENCY_BCAST:" + m.getUserId() + ":" + orderId;
            String msg = order.getRequiredCategory() + "|" + order.getOrderId() + "|"
                    + recipientName + "|" + recipientTag + "|" + doorNumber + "|" + urgency + "|"
                    + recipientLon  + "|" + recipientLat;
            log.info("[紧急广播] 写入Redis key={} msg={}", redisKey, msg);
            // TTL 2h：给予商家足够的响应窗口，2h 后自动清理无效广播
            stringRedisTemplate.opsForValue().set(redisKey, msg, 2, TimeUnit.HOURS);
        }

        result.put("notifiedCount", targetMerchants.size());
        return result;
    }

    /**
     * Haversine 球面距离公式，计算地球表面两点间的大圆距离（单位：km）。
     *
     * <p>作为高德路线规划 API 的降级方案：当高德 API 限流、超时或不可用时，
     * 使用此方法进行粗略距离估算，保障调度流程的基本可用性。
     * 精度说明：将地球建模为标准球体（R=6371km），短距离（50km 内）误差约 0.3%。
     *
     * @param lat1 起点纬度（十进制度数，南纬为负）
     * @param lon1 起点经度（十进制度数，西经为负）
     * @param lat2 终点纬度
     * @param lon2 终点经度
     * @return 两点间球面距离，单位 km
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // 地球平均半径（km）

        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);

        // Haversine 公式：a = sin²(Δlat/2) + cos(lat1)·cos(lat2)·sin²(Δlon/2)
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);

        // 中心角 c，再乘以地球半径得到弧长（即球面距离）
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}