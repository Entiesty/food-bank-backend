package com.foodbank.module.trade.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.dispatch.strategy.MultiFactorDispatchStrategy;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.service.IStationService;
import com.foodbank.module.system.user.entity.CreditLog;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.ICreditLogService;
import com.foodbank.module.system.user.service.IUserService;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.order.mapper.DispatchOrderMapper;
import com.foodbank.module.dispatch.model.dto.DemandPublishDTO;
import com.foodbank.module.trade.order.model.dto.RespondSosDTO;
import com.foodbank.module.trade.order.model.vo.AvailableOrderVO;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.trade.task.service.IDeliveryTaskService;
import com.foodbank.websocket.WebSocketServer;
import com.foodbank.module.system.config.entity.Config;
import com.foodbank.module.system.config.service.IConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DispatchOrderServiceImpl extends ServiceImpl<DispatchOrderMapper, DispatchOrder> implements IDispatchOrderService {

    @Autowired
    private IStationService stationService;

    @Autowired
    private IUserService userService;

    @Autowired
    private MultiFactorDispatchStrategy dispatchStrategy;

    @Autowired
    private IDeliveryTaskService taskService;

    @Autowired
    private ICreditLogService creditLogService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.foodbank.module.resource.goods.service.IGoodsService goodsService;

    @Autowired
    private IConfigService configService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.foodbank.module.dispatch.service.impl.DispatchEngineServiceImpl dispatchEngineService;

    private void enrichOrderNames(List<DispatchOrder> orders) {
        if (orders == null || orders.isEmpty()) return;
        for (DispatchOrder order : orders) {
            if (order.getOrderType() != null && order.getOrderType() == 1) {
                if (order.getSourceId() != null) {
                    User merchant = userService.getById(order.getSourceId());
                    if (merchant != null) {
                        order.setSourceName(merchant.getUsername() + " (爱心商铺)");
                        order.setSourceLon(merchant.getCurrentLon());
                        order.setSourceLat(merchant.getCurrentLat());
                    }
                }
                if (order.getDestId() != null) {
                    Station station = stationService.getById(order.getDestId());
                    if (station != null) order.setTargetName(station.getStationName());
                }
            } else {
                if (order.getSourceId() != null) {
                    Station station = null;
                    if (order.getSourceId() < 0) {
                        User merchant = userService.getById(-order.getSourceId());
                        if (merchant != null) {
                            order.setSourceName(merchant.getUsername() + " (爱心商铺直发)");
                            order.setSourceLon(merchant.getCurrentLon());
                            order.setSourceLat(merchant.getCurrentLat());
                        }
                    } else {
                        station = stationService.getById(order.getSourceId());
                        if (station != null) {
                            order.setSourceName(station.getStationName());
                            order.setSourceLon(station.getLongitude());
                            order.setSourceLat(station.getLatitude());
                        }
                    }
                }
                if (order.getDestId() != null) {
                    User recipient = userService.getById(order.getDestId());
                    if (recipient != null) order.setTargetName(recipient.getUsername() + " (求助市民)");
                }
            }
        }
    }

    @Override
    public List<DispatchOrder> getPendingOrdersForMap(Double currentLon, Double currentLat) {
        List<DispatchOrder> list = this.list(new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, (byte) 0)
                .ne(DispatchOrder::getDeliveryMethod, (byte) 2)
                .orderByDesc(DispatchOrder::getUrgencyLevel)
                .orderByDesc(DispatchOrder::getCreateTime));
        enrichOrderNames(list);

        // 【载具容量预过滤】剔除当前志愿者无法装载的订单
        Long userId = UserContext.getUserId();
        if (userId != null) {
            User vol = userService.getById(userId);
            if (vol != null) {
                list = list.stream().filter(o -> canVolunteerCarry(o, vol)).collect(java.util.stream.Collectors.toList());
            }
        }

        // 🚀 统一 SAW 多因子评分：与抢单大厅 rankOrdersForVolunteer 共用权重体系
        if (currentLon != null && currentLat != null) {
            Config cfg = configService.getCurrentConfig();
            double wDist = cfg.getWDist().doubleValue();
            double wUrgency = cfg.getWUrgency().doubleValue();
            double wCredit = cfg.getWCredit().doubleValue();
            double wTimeCoin = cfg.getWTimeCoin() != null ? cfg.getWTimeCoin().doubleValue() : 0.05;

            // 读取志愿者信用与时间币 (复用上方已声明的 userId)
            int volCredit = 100;
            int timeCoin = 0;
            if (userId != null) {
                // 如果前面容量过滤已查过，这里直接复用缓存；MyBatis Plus 一级缓存会自动去重
                User vol = userService.getById(userId);
                if (vol != null && vol.getCreditScore() != null) volCredit = vol.getCreditScore();
                if (vol != null && vol.getTimeCoin() != null) timeCoin = vol.getTimeCoin();
            }

            // 计算接驾距离并找极值
            double maxDist = 1.0;
            double minDist = Double.MAX_VALUE;
            for (DispatchOrder o : list) {
                if (o.getSourceLon() != null && o.getSourceLat() != null) {
                    double d = haversine(currentLat, currentLon,
                            o.getSourceLat().doubleValue(), o.getSourceLon().doubleValue());
                    o.setMatchScore(d); // 暂存距离用于后续归一化
                    if (d > maxDist) maxDist = d;
                    if (d < minDist) minDist = d;
                } else {
                    o.setMatchScore(999.0);
                }
            }
            double distRange = Math.max(maxDist - minDist, 1.0);
            double normCredit = Math.min(volCredit / 150.0, 1.0);
            double normTimeCoin = Math.min(timeCoin / 50.0, 1.0);

            for (DispatchOrder o : list) {
                double normDist = (o.getMatchScore() != null && o.getMatchScore() != 999.0)
                        ? ((maxDist - o.getMatchScore()) / distRange) : 0.0;
                double normUrgency = (o.getUrgencyLevel() != null ? o.getUrgencyLevel() : 1) / 10.0;
                o.setMatchScore(normDist * wDist + normUrgency * wUrgency + normCredit * wCredit + normTimeCoin * wTimeCoin);
            }

            list.sort((a, b) -> Double.compare(b.getMatchScore(), a.getMatchScore()));
        }
        return list;
    }

    /**
     * 双维绝对阈值校验（严格 1对1 履约，不累计）: 重量或体积任一超限即拦截
     */
    private boolean canVolunteerCarry(DispatchOrder order, User volunteer) {
        if (order.getGoodsId() == null) return true;
        com.foodbank.module.resource.goods.entity.Goods goods = goodsService.getById(order.getGoodsId());
        if (goods == null) return true;

        Integer vType = volunteer.getVehicleType() != null ? volunteer.getVehicleType() : 1;
        int maxW = vType == 1 ? 2 : (vType == 2 ? 4 : (vType == 3 ? 10 : 100));
        int maxV = vType == 1 ? 2 : (vType == 2 ? 5 : (vType == 3 ? 15 : 100));

        int wl = goods.getWeightLevel() != null ? goods.getWeightLevel() : 1;
        int vl = goods.getVolumeLevel() != null ? goods.getVolumeLevel() : 1;
        int wp = wl == 3 ? 20 : (wl == 2 ? 5 : 1);
        int vp = vl == 3 ? 40 : (vl == 2 ? 5 : 1);

        return wp <= maxW && vp <= maxV;
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @Override
    public Page<DispatchOrder> getAdminOrderPage(int pageNum, int pageSize, String orderSn, Byte status, Byte deliveryMethod) {
        Page<DispatchOrder> pageReq = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<DispatchOrder> wrapper = new LambdaQueryWrapper<>();
        if (orderSn != null && !orderSn.trim().isEmpty()) wrapper.like(DispatchOrder::getOrderSn, orderSn);
        if (status != null) wrapper.eq(DispatchOrder::getStatus, status);
        if (deliveryMethod != null) wrapper.eq(DispatchOrder::getDeliveryMethod, deliveryMethod);
        wrapper.orderByDesc(DispatchOrder::getCreateTime);

        Page<DispatchOrder> page = this.page(pageReq, wrapper);
        enrichOrderNames(page.getRecords());
        return page;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void respondToSos(RespondSosDTO dto) {
        Long merchantId = UserContext.getUserId();
        if (merchantId == null) throw new BusinessException("商家身份异常，请重新登录");

        User merchant = userService.getById(merchantId);
        if (merchant == null || merchant.getCurrentLon() == null || merchant.getCurrentLat() == null)
            throw new BusinessException("请先在个人中心设置店铺坐标");

        // 1. 入库物资
        com.foodbank.module.resource.goods.entity.Goods goods = new com.foodbank.module.resource.goods.entity.Goods();
        goods.setMerchantId(merchantId);
        goods.setGoodsName(dto.getGoodsName());
        goods.setCategory(dto.getCategory());
        goods.setStock(dto.getStock());
        goods.setUnit(dto.getUnit() != null ? dto.getUnit() : "件");
        goods.setExpirationDate(dto.getExpirationDate());
        goods.setVolumeLevel(dto.getVolumeLevel() != null ? dto.getVolumeLevel() : 1);
        goods.setWeightLevel(dto.getWeightLevel() != null ? dto.getWeightLevel() : 1);
        goods.setGoodsImageUrl(dto.getGoodsImageUrl() != null ? dto.getGoodsImageUrl() : "/img/default.png");
        goods.setEstimatedValue(dto.getEstimatedValue() != null ? dto.getEstimatedValue() : java.math.BigDecimal.ZERO);
        goods.setCurrentStationId(null); // P2P 直达，不经过驿站
        goods.setStatus((byte) 0);
        goods.setTags("[]");

        com.foodbank.module.resource.goods.service.IGoodsService gs = goodsService;
        if (!gs.save(goods)) throw new BusinessException("物资入库失败");

        // 2. CAS 乐观锁：在 SOS 订单上直接写入 source_id/goods_id
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DispatchOrder> wrapper =
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DispatchOrder>()
                .eq(DispatchOrder::getOrderId, dto.getOrderId())
                .isNull(DispatchOrder::getSourceId)     // 守卫：只允许第一个响应者
                .eq(DispatchOrder::getStatus, 0)         // 仅待处理状态
                .set(DispatchOrder::getSourceId, merchantId)
                .set(DispatchOrder::getSourceLon, merchant.getCurrentLon())
                .set(DispatchOrder::getSourceLat, merchant.getCurrentLat())
                .set(DispatchOrder::getGoodsId, goods.getGoodsId())
                .set(DispatchOrder::getGoodsName, dto.getGoodsName())
                .set(DispatchOrder::getGoodsCount, dto.getStock());

        boolean updated = this.update(wrapper);
        if (!updated) throw new BusinessException("该求助已被其他爱心商家抢先响应");

        // 3. WebSocket 通知受赠方
        DispatchOrder sos = this.getById(dto.getOrderId());
        if (sos != null && sos.getDestId() != null) {
            try {
                String msg = "{\"type\":\"SOS_RESPONDED\",\"orderSn\":\"" + sos.getOrderSn() + "\",\"msg\":\"您的求救已被响应！物资即将直达！\"}";
                WebSocketServer.sendMessageToUser(sos.getDestId(), msg);
            } catch (Exception e) {
                log.error("推送SOS响应通知失败", e);
            }
        }

        // 4. 🚨 骑手全网红色强推：商户已备好物资，急需骑士接力配送 (带物資体量信息)
        try {
            int wl = dto.getWeightLevel() != null ? dto.getWeightLevel() : 1;
            int vl = dto.getVolumeLevel() != null ? dto.getVolumeLevel() : 1;
            String[] weightLabels = {"", "轻量<5kg", "标准5-20kg", "重载>20kg"};
            String[] volumeLabels = {"", "小件", "中件", "大件"};
            String cargoInfo = weightLabels[Math.min(wl, 3)] + " · " + volumeLabels[Math.min(vl, 3)];
            String urgentMsg = "{\"type\":\"URGENT_TASK_READY\"," +
                "\"message\":\"🚨 紧急！周边商户已备好救命物资，急需骑士前往接力配送！\"," +
                "\"orderId\":\"" + dto.getOrderId() + "\"," +
                "\"cargoInfo\":\"" + cargoInfo + "\"," +
                "\"weightLevel\":" + wl + "," +
                "\"volumeLevel\":" + vl + "}";
            WebSocketServer.broadcast(urgentMsg);
            log.info("📡 全网骑手强推广播已发送, orderId={}, cargo={}", dto.getOrderId(), cargoInfo);
        } catch (Exception e) {
            log.error("全网骑手强推广播失败, orderId={}", dto.getOrderId(), e);
        }

        // 4.5 🧹 商家成功接单后，清除该 orderId 的所有广播，防止其他商家重复看到
        try {
            java.util.Set<String> broadcastKeys = stringRedisTemplate.keys("EMERGENCY_BCAST:*:" + dto.getOrderId());
            if (broadcastKeys != null && !broadcastKeys.isEmpty()) {
                stringRedisTemplate.delete(broadcastKeys);
                log.info("🧹 订单 {} 已被商家 {} 响应，已清除 {} 条关联广播", dto.getOrderId(), merchantId, broadcastKeys.size());
            }
        } catch (Exception e) {
            log.warn("清除广播缓存异常(非致命)", e);
        }

        // 5. 🚀 商家自配送分支：商户自己送，跳过骑手抢单
        if (dto.getIsSelfDelivery() != null && dto.getIsSelfDelivery()) {
            this.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DispatchOrder>()
                    .eq(DispatchOrder::getOrderId, dto.getOrderId())
                    .eq(DispatchOrder::getStatus, 0)
                    .set(DispatchOrder::getStatus, 1));

            DeliveryTask task = new DeliveryTask();
            task.setOrderId(dto.getOrderId());
            task.setVolunteerId(merchantId);
            task.setTaskStatus((byte) 1);
            task.setVersion(0);
            taskService.save(task);
            log.info("🚀 商家自配送模式已激活, orderId={}, merchantId={}", dto.getOrderId(), merchantId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String publishDemandOrder(DemandPublishDTO dto) {
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) throw new BusinessException("用户信息获取失败，请重新登录");

        // 灾区配给制: 应急模式下检查每日申领配额
        Config config = configService.getCurrentConfig();
        if ("EMERGENCY".equals(config.getSysMode())) {
            checkRationQuota(currentUserId, config);
        }

        // ✅ FIX-2A: 守卫A — 应急模式物理隔离, 禁止自提
        if ("EMERGENCY".equals(config.getSysMode())
                && dto.getDeliveryMethod() != null && dto.getDeliveryMethod() == 2) {
            throw new BusinessException("🚨 应急响应期间严禁市民自行外出，已关闭自提通道，请使用紧急呼救申请上门配送！");
        }

        // ✅ 守卫A2: 平时模式下预约上门配送 → 需验证15km内有库存，无货或距离太远则友好拒绝
        // 暂存最近驿站匹配结果 (在空间校验中填充，订单创建时消费)
        Long matchedStationId = null;
        Long matchedGoodsId = null;
        String matchedGoodsName = null;
        if (!"EMERGENCY".equals(config.getSysMode())
                && (dto.getDeliveryMethod() == null || dto.getDeliveryMethod() == 1)) {
            java.util.List<String> checkCats = com.foodbank.module.resource.goods.model.CategoryHierarchy.expand(dto.getRequiredCategory());

            // 全局快速短路：全城一粒库存都没有 → 直接拒绝
            long totalAvailable = goodsService.count(new LambdaQueryWrapper<com.foodbank.module.resource.goods.entity.Goods>()
                    .in(com.foodbank.module.resource.goods.entity.Goods::getCategory, checkCats)
                    .eq(com.foodbank.module.resource.goods.entity.Goods::getStatus, 2)
                    .gt(com.foodbank.module.resource.goods.entity.Goods::getStock, 0));
            if (totalAvailable == 0) {
                throw new BusinessException("当前为平时模式，全城暂无 [" + dto.getRequiredCategory() + "] 类物资库存。建议前往「食物银行公益超市」选择已有物资自提，或等待爱心商家补货后重试。");
            }

            // 空间邻近校验 + 自动匹配最近驿站物资：找到 15km 内最近的据点并锁定物资
            if (dto.getTargetLon() != null && dto.getTargetLat() != null) {
                java.math.BigDecimal reqLon = dto.getTargetLon();
                java.math.BigDecimal reqLat = dto.getTargetLat();
                // 1. 一次查询拉取所有匹配物资
                java.util.List<com.foodbank.module.resource.goods.entity.Goods> nearbyCandidates = goodsService.list(
                    new LambdaQueryWrapper<com.foodbank.module.resource.goods.entity.Goods>()
                        .in(com.foodbank.module.resource.goods.entity.Goods::getCategory, checkCats)
                        .eq(com.foodbank.module.resource.goods.entity.Goods::getStatus, 2)
                        .gt(com.foodbank.module.resource.goods.entity.Goods::getStock, 0));
                // 2. 聚合去重 stationId 后批量加载驿站 (消除 N+1)
                java.util.Set<Long> stationIds = nearbyCandidates.stream()
                        .map(com.foodbank.module.resource.goods.entity.Goods::getCurrentStationId)
                        .filter(id -> id != null)
                        .collect(Collectors.toSet());
                java.util.Map<Long, com.foodbank.module.resource.station.entity.Station> stationMap = new java.util.HashMap<>();
                if (!stationIds.isEmpty()) {
                    stationService.listByIds(stationIds).forEach(st -> stationMap.put(st.getStationId(), st));
                }
                // 3. 内存计算找到距离受赠方最近的驿站+物资，同时完成 15km 校验
                double bestDist = Double.MAX_VALUE;
                for (com.foodbank.module.resource.goods.entity.Goods g : nearbyCandidates) {
                    if (g.getCurrentStationId() == null) continue;
                    com.foodbank.module.resource.station.entity.Station st = stationMap.get(g.getCurrentStationId());
                    if (st == null || st.getLongitude() == null || st.getLatitude() == null) continue;
                    double dist = haversine(reqLat.doubleValue(), reqLon.doubleValue(),
                                            st.getLatitude().doubleValue(), st.getLongitude().doubleValue());
                    if (dist <= 15.0 && dist < bestDist) {
                        bestDist = dist;
                        matchedStationId = st.getStationId();
                        matchedGoodsId = g.getGoodsId();
                        matchedGoodsName = g.getGoodsName();
                    }
                }
                if (matchedStationId == null) {
                    throw new BusinessException("当前为平时模式， [" + dto.getRequiredCategory() + "] 类物资最近的据点也在 15km 以外。建议前往「食物银行公益超市」选择已有物资自提，或等待爱心商家补货后重试。");
                }
            }
        }

        // ✅ FIX-2B: 守卫B — 权限对齐, 仅限上门用户禁止自提
        if (dto.getDeliveryMethod() != null && dto.getDeliveryMethod() == 2) {
            User currentUser = userService.getById(currentUserId);
            if (currentUser != null && currentUser.getDeliveryType() != null
                    && currentUser.getDeliveryType() == 1) {
                throw new BusinessException("您的档案登记为行动不便，不支持自行前往食物银行取货。");
            }
        }

        DispatchOrder dispatchOrder = new DispatchOrder();

        // 🚨 核心重构：根据紧急度动态分配三轨制业务前缀
        // 紧急度 >= 6 判定为紧急呼救 (SOS)，低于 6 判定为日常申领 (REQ)
        String prefix = "REQ-";
        if (dto.getUrgencyLevel() != null && dto.getUrgencyLevel() >= 6) {
            prefix = "SOS-";
        }

        // 动态拼接单号
        dispatchOrder.setOrderSn(prefix + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());

        dispatchOrder.setOrderType((byte) 2);
        dispatchOrder.setDestId(currentUserId);
        dispatchOrder.setRequiredCategory(dto.getRequiredCategory());

        if (dto.getRequiredTags() != null && !dto.getRequiredTags().isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                String jsonTags = mapper.writeValueAsString(dto.getRequiredTags());
                dispatchOrder.setRequiredTags(jsonTags);
            } catch (Exception e) {
                dispatchOrder.setRequiredTags("[]");
            }
        } else {
            dispatchOrder.setRequiredTags("[]");
        }

        dispatchOrder.setUrgencyLevel(dto.getUrgencyLevel() != null ? dto.getUrgencyLevel().byteValue() : 1);
        dispatchOrder.setTargetLon(dto.getTargetLon());
        dispatchOrder.setTargetLat(dto.getTargetLat());
        dispatchOrder.setDeliveryMethod(dto.getDeliveryMethod() != null ? dto.getDeliveryMethod().byteValue() : (byte) 1);

        if (dispatchOrder.getDeliveryMethod() == 2 && dto.getGoodsId() != null) {
            // 自提: 原子乐观锁扣减, 锁定指定物资
            boolean success = goodsService.deductStockSafe(dto.getGoodsId(), 1);
            if (!success) {
                throw new BusinessException("手慢了！该物资已被抢空！");
            }

            String code = String.valueOf((int)((Math.random() * 9 + 1) * 100000));
            dispatchOrder.setPickupCode(code);
            dispatchOrder.setGoodsId(dto.getGoodsId());
            dispatchOrder.setSourceId(dto.getSourceId());
            dispatchOrder.setStatus((byte) 1);
            dispatchOrder.setGoodsName(dto.getDescription() != null ? dto.getDescription() : dto.getRequiredCategory());
            dispatchOrder.setGoodsCount(1);
        } else if (matchedStationId != null) {
            // 平时模式 + 预约上门配送: 自动匹配最近驿站物资, 原子扣减库存
            boolean success = goodsService.deductStockSafe(matchedGoodsId, 1);
            if (!success) {
                throw new BusinessException("手慢了！该物资已被抢空，请重新提交求助。");
            }
            dispatchOrder.setSourceId(matchedStationId);
            dispatchOrder.setGoodsId(matchedGoodsId);
            dispatchOrder.setGoodsName(matchedGoodsName);
            dispatchOrder.setGoodsCount(1);
            dispatchOrder.setStatus((byte) 0);
        } else {
            // 应急模式或无匹配: 待商家响应
            dispatchOrder.setStatus((byte) 0);
            dispatchOrder.setGoodsName("急需：" + (dto.getDescription() != null ? dto.getDescription() : dto.getRequiredCategory()));
            dispatchOrder.setGoodsCount(1);
        }

        boolean saved = this.save(dispatchOrder);
        if (!saved) throw new BusinessException("求助发布失败，请稍后重试");

        try {
            String msgType = dispatchOrder.getOrderSn().startsWith("SOS") ? "NEW_SOS" : "NEW_REQ";
            String jsonMsg = String.format("{\"type\":\"%s\", \"orderSn\":\"%s\"}", msgType, dispatchOrder.getOrderSn());
            WebSocketServer.broadcast(jsonMsg);
        } catch (Exception e) {
            log.error("WebSocket 订单通知广播失败", e);
        }

        // ✅ FIX-4: SOS紧急订单自动触发LBS雷达广播 (urgency>=8, 无需管理员手动点击)
        if (dispatchOrder.getUrgencyLevel() != null && dispatchOrder.getUrgencyLevel() >= 8) {
            try {
                dispatchEngineService.triggerEmergencyBroadcast(dispatchOrder.getOrderId());
                log.info("🚨 SOS自动雷达已激活, 单号: {}", dispatchOrder.getOrderSn());
            } catch (Exception e) {
                log.error("SOS自动雷达广播失败, 将降级为手动触发", e);
            }
        }

        return dispatchOrder.getPickupCode();
    }

    @Override
    public Page<AvailableOrderVO> getAvailableOrderPage(int pageNum, int pageSize) {
        Page<DispatchOrder> pageReq = new Page<>(pageNum, pageSize);
        // 拦截自提单与脏数据
        Page<DispatchOrder> orderPage = this.page(pageReq, new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, 0)
                .eq(DispatchOrder::getDeliveryMethod, 1)
                .isNotNull(DispatchOrder::getSourceId)
                .and(w -> w.isNotNull(DispatchOrder::getDestId).or().isNotNull(DispatchOrder::getTargetLon))
                .orderByDesc(DispatchOrder::getUrgencyLevel)
                .orderByDesc(DispatchOrder::getCreateTime));

        // 加载志愿者信息（SAW 排序用）
        Long userId = UserContext.getUserId();
        User volunteer = userService.getById(userId);

        List<AvailableOrderVO> voList = orderPage.getRecords().stream().map(order -> {
            String sourceName = "未知起点";
            String sourceAddress = "位置待确认";
            BigDecimal sourceLon = null;
            BigDecimal sourceLat = null;
            String targetName = "未知终点";
            String targetAddress = "位置待确认";
            BigDecimal targetLon = order.getTargetLon();
            BigDecimal targetLat = order.getTargetLat();

            if (order.getOrderType() != null && order.getOrderType() == 1) {
                // 常规捐赠单 (DON): 商家发往驿站
                User merchant = userService.getById(order.getSourceId());
                if (merchant != null) {
                    sourceName = merchant.getUsername() + " (爱心商铺)";
                    sourceAddress = "联系电话: " + merchant.getPhone();
                    sourceLon = merchant.getCurrentLon();
                    sourceLat = merchant.getCurrentLat();
                }
                Station station = stationService.getById(order.getDestId());
                if (station != null) {
                    targetName = station.getStationName();
                    targetAddress = station.getAddress();
                    targetLon = station.getLongitude();
                    targetLat = station.getLatitude();
                }
            } else {
                // 求助单 (SOS): 驿站发往市民，或者 P2P(商家直供市民)
                boolean isP2P = false;
                if (order.getGoodsId() != null) {
                    com.foodbank.module.resource.goods.entity.Goods goods = goodsService.getById(order.getGoodsId());
                    // 💡 核心鉴别逻辑：如果物资没有绑定任何驿站，说明它是商家手里刚出炉的 P2P 直供单！
                    if (goods != null && goods.getCurrentStationId() == null) {
                        isP2P = true;
                    }
                }

                if (isP2P) {
                    // 🚨 P2P 战时模式：起点变更为商铺
                    User merchant = userService.getById(order.getSourceId());
                    if (merchant != null) {
                        sourceName = merchant.getUsername() + " (🚨定向直供商铺)";
                        sourceAddress = "联系电话: " + merchant.getPhone();
                        sourceLon = merchant.getCurrentLon();
                        sourceLat = merchant.getCurrentLat();
                    }
                } else {
                    // 常规模式：起点依然是驿站
                    Station station = stationService.getById(order.getSourceId());
                    if (station != null) {
                        sourceName = station.getStationName();
                        sourceAddress = station.getAddress();
                        sourceLon = station.getLongitude();
                        sourceLat = station.getLatitude();
                    }
                }

                User recipient = userService.getById(order.getDestId());
                if (recipient != null) {
                    targetName = recipient.getUsername() + " (求助市民)";
                    targetAddress = "联系电话: " + recipient.getPhone();
                    if (targetLon == null) targetLon = recipient.getCurrentLon();
                    if (targetLat == null) targetLat = recipient.getCurrentLat();
                }
            }
            // 查询物资双维等级 + 单位供前端展示
            int wl = 1, vl = 1;
            String unit = "份";
            if (order.getGoodsId() != null) {
                com.foodbank.module.resource.goods.entity.Goods g = goodsService.getById(order.getGoodsId());
                if (g != null) { wl = g.getWeightLevel(); vl = g.getVolumeLevel(); unit = g.getUnit() != null ? g.getUnit() : "份"; }
            }
            return AvailableOrderVO.builder()
                    .orderId(order.getOrderId()).orderSn(order.getOrderSn())
                    .goodsName(order.getGoodsName()).goodsCount(order.getGoodsCount())
                    .requiredCategory(order.getRequiredCategory()).urgencyLevel(order.getUrgencyLevel())
                    .sourceName(sourceName).sourceAddress(sourceAddress).sourceLon(sourceLon).sourceLat(sourceLat)
                    .targetName(targetName).targetAddress(targetAddress).targetLon(targetLon).targetLat(targetLat)
                    .createTime(order.getCreateTime())
                    .weightLevel(wl).volumeLevel(vl).goodsUnit(unit).orderType(order.getOrderType()).build();
        }).collect(Collectors.toList());

        if (volunteer != null) {
            Double volLon = volunteer.getCurrentLon() != null ? volunteer.getCurrentLon().doubleValue() : null;
            Double volLat = volunteer.getCurrentLat() != null ? volunteer.getCurrentLat().doubleValue() : null;
            int creditScore = volunteer.getCreditScore() != null ? volunteer.getCreditScore() : 100;
            int timeCoin = volunteer.getTimeCoin() != null ? volunteer.getTimeCoin() : 0;
            dispatchStrategy.rankOrdersForVolunteer(voList, volLon, volLat, creditScore, timeCoin);
        }

        Page<AvailableOrderVO> resultPage = new Page<>(pageNum, pageSize, orderPage.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void switchOrderToPickup(Long orderId) {
        DispatchOrder order = this.getById(orderId);
        if (order == null || order.getStatus() == 2 || order.getStatus() == 3) {
            throw new BusinessException("订单状态异常或已被处理，无法转为自提");
        }
        order.setDeliveryMethod((byte) 2);
        boolean updated = this.updateById(order);
        if (!updated) throw new BusinessException("运力熔断触发失败，数据库更新异常");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        DispatchOrder order = this.getById(orderId);
        if (order == null) throw new BusinessException("订单不存在");
        if (order.getStatus() >= 2) throw new BusinessException("志愿者已送达或订单已完成，无法撤销");

        order.setStatus((byte) 4);
        boolean updated = this.updateById(order);
        if (!updated) throw new BusinessException("撤销失败，请重试");

        // 【级联作废已分配的任务】若骑手已抢单但未取货，同步作废任务防止幽灵数据
        DeliveryTask task = taskService.getOne(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getOrderId, orderId));
        if (task != null) {
            if (task.getTaskStatus() >= 2) {
                throw new BusinessException("志愿者已取货正在途中，无法撤销订单");
            }
            task.setTaskStatus((byte) 0);
            taskService.updateById(task);
        }

        // 【库存兜底回滚】自提订单取消后释放已锁定的物资
        if (order.getGoodsId() != null) {
            com.foodbank.module.resource.goods.entity.Goods goods = goodsService.getById(order.getGoodsId());
            if (goods != null) {
                if (order.getDeliveryMethod() != null && order.getDeliveryMethod() == 2) {
                    goods.setStock(goods.getStock() + 1);
                }
                if (goods.getStatus() == 3) goods.setStatus((byte) 2);
                goodsService.updateById(goods);
            }
        }

        // 【WebSocket 广播】推送给所有在线骑手，触发前端列表刷新
        try {
            String jsonMsg = "{\"type\":\"ORDER_CANCELLED\",\"orderId\":" + orderId
                    + ",\"orderSn\":\"" + order.getOrderSn() + "\"}";
            WebSocketServer.broadcast(jsonMsg);
        } catch (Exception e) {
            log.error("取消订单 WebSocket 广播失败 orderId=" + orderId, e);
        }

        // 【定向通知受赠方】告知求助已被指挥中心撤销
        if (order.getDestId() != null) {
            try {
                String cancelMsg = "{\"type\":\"ORDER_CANCELLED\",\"orderId\":" + orderId
                        + ",\"orderSn\":\"" + order.getOrderSn() + "\""
                        + ",\"message\":\"您的紧急求助已被指挥中心撤销，如有需要请重新发布。\"}";
                WebSocketServer.sendMessageToUser(order.getDestId(), cancelMsg);
            } catch (Exception e) {
                log.error("通知受赠方订单取消失败 destId={}", order.getDestId(), e);
            }
        }

        // 【清理紧急广播】订单已取消，清除所有商家的 Redis 广播缓存
        try {
            java.util.Set<String> broadcastKeys = stringRedisTemplate.keys("EMERGENCY_BCAST:*:" + orderId);
            if (broadcastKeys != null && !broadcastKeys.isEmpty()) {
                stringRedisTemplate.delete(broadcastKeys);
                log.info("🧹 订单 {} 已取消，已清除 {} 条紧急广播缓存", orderId, broadcastKeys.size());
            }
        } catch (Exception e) {
            log.warn("清除紧急广播缓存异常(非致命) orderId={}", orderId, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceiptAndRate(Long orderId, Long userId, Integer rating, String comment) {
        // 1. 基础校验 (只读查询)
        DispatchOrder order = this.getById(orderId);
        if (order == null || !order.getDestId().equals(userId)) {
            throw new BusinessException("非法操作：订单不存在或您不是该订单的受赠方");
        }

        // ==========================================
        // 🚀 行级乐观锁状态跃迁 (防并发刷单)
        // ==========================================
        boolean updated = this.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DispatchOrder>()
                .eq(DispatchOrder::getOrderId, orderId)
                .eq(DispatchOrder::getStatus, 2) // 核心锁：只有当前数据库里真实状态是 2 才能更新成功！
                .set(DispatchOrder::getStatus, 3)
                .set(DispatchOrder::getRecipientRating, rating)
                .set(DispatchOrder::getRecipientComment, comment));

        if (!updated) {
            throw new BusinessException("该订单状态已改变或您已评价过，请勿重复提交！");
        }

        // ==========================================
        // 🚀 核心逻辑二：为【护航骑士】发放信誉分 (打通 0 分静默限制)
        // ==========================================
        DeliveryTask task = taskService.getOne(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getOrderId, orderId)
                .eq(DeliveryTask::getTaskStatus, 3)
                .last("LIMIT 1"));

        if (task != null) {
            Long volunteerId = task.getVolunteerId();
            int creditDelta = 0;

            // 分数映射规则
            if (rating == 5) creditDelta = 5;
            else if (rating == 4) creditDelta = 2;
            else if (rating == 2) creditDelta = -5;
            else if (rating == 1) creditDelta = -15;

            // 🚨 核心修复：无论 creditDelta 是不是 0，都必须写日志记录留存案底
            User volunteer = userService.getById(volunteerId);
            if (volunteer != null) {
                int oldScore = volunteer.getCreditScore() != null ? volunteer.getCreditScore() : 100;
                volunteer.setCreditScore(oldScore + creditDelta);
                userService.updateById(volunteer);

                CreditLog creditLog = new CreditLog();
                creditLog.setUserId(volunteerId);
                creditLog.setOrderId(orderId);
                creditLog.setChangeValue(creditDelta);

                String vReason = "订单完结评价: " + rating + "星";
                if (creditDelta == 0) {
                    vReason += " (无积分变动)";
                }
                creditLog.setReason(vReason + (comment != null && !comment.isEmpty() ? " (" + comment + ")" : ""));
                creditLogService.save(creditLog);
            }
        }

        // ==========================================
        // 🚀 核心逻辑三：溯源双向反哺，给幕后【爱心商家】加分 (打通 0 分静默限制)
        // ==========================================
        if (order.getGoodsId() != null) {
            com.foodbank.module.resource.goods.entity.Goods goods = goodsService.getById(order.getGoodsId());
            if (goods != null && goods.getMerchantId() != null) {
                User merchant = userService.getById(goods.getMerchantId());
                if (merchant != null && merchant.getRole() == 2) {
                    int merchantDelta = (rating == 5) ? 3 : (rating == 4 ? 1 : 0);

                    // 🚨 核心修复：无论 merchantDelta 是否大于 0，都强制写入流水追踪表
                    int oldScore = merchant.getCreditScore() != null ? merchant.getCreditScore() : 100;
                    merchant.setCreditScore(oldScore + merchantDelta);
                    userService.updateById(merchant);

                    CreditLog merchantLog = new CreditLog();
                    merchantLog.setUserId(merchant.getUserId());
                    merchantLog.setOrderId(orderId);
                    merchantLog.setChangeValue(merchantDelta);

                    // 动态生成直观的账单解释说明
                    String mReason = "受助方确认收货并给予 " + rating + " 星评价";
                    if (merchantDelta == 0) {
                        mReason += " (未达到奖励标准，无积分加成)";
                    }
                    merchantLog.setReason(mReason);
                    creditLogService.save(merchantLog);
                }
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void verifyPickupCode(String pickupCode) {
        Long verifierId = UserContext.getUserId();
        User verifier = userService.getById(verifierId);

        // 🚨 1. 核心修改：权限极度收敛，踢掉商家(Role 2)，只认管理员(Role 4)
        if (verifier.getRole() == null || verifier.getRole() != 4) {
            throw new BusinessException("非法越权访问：仅食物银行驿站管理员可执行线下核销！");
        }

        DispatchOrder order = this.getOne(new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getPickupCode, pickupCode)
                .eq(DispatchOrder::getDeliveryMethod, 2));

        if (order == null) throw new BusinessException("取件码错误或该订单不存在！");
        if (order.getStatus() == 3) throw new BusinessException("该取件码已被核销过，请勿重复操作！");
        if (order.getStatus() == 0) throw new BusinessException("物资还在准备中，暂不能核销！");

        order.setStatus((byte) 2);
        this.updateById(order);

        int oldScore = verifier.getCreditScore() != null ? verifier.getCreditScore() : 0;
        verifier.setCreditScore(oldScore + 2);
        userService.updateById(verifier);

        CreditLog creditLog = new CreditLog();
        creditLog.setUserId(verifierId);
        creditLog.setOrderId(order.getOrderId());
        creditLog.setChangeValue(2);
        // 🚨 2. 核心修改：文案改为管理员专属文案即可
        creditLog.setReason("驿站管理员协助市民完成线下核销提货");
        creditLog.setCreateTime(LocalDateTime.now());
        creditLogService.save(creditLog);
    }

    @Override
    public List<java.util.Map<String, Object>> getGoodsDistributionDetails(Long goodsId) {
        // 🛡️ 防线一：SQL 层拦截，剔除捐赠入库单 (order_type = 1 的 DON 单)
        List<DispatchOrder> orders = this.list(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getGoodsId, goodsId)
                .ne(DispatchOrder::getOrderType, (byte) 1) // 🚨 核心修改：不等于 1 (即只要 SOS 和 自提申领单)
                .ge(DispatchOrder::getStatus, (byte) 2)
                .orderByDesc(DispatchOrder::getCreateTime));

        List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        for (DispatchOrder order : orders) {
            if (order.getDestId() != null) {
                User recipient = userService.getById(order.getDestId());

                // 🛡️ 防线二：业务层拦截，必须是真正的受助方 (Role = 1)
                if (recipient != null && recipient.getRole() == 1) {
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("orderId", order.getOrderId());
                    map.put("goodsCount", order.getGoodsCount());
                    map.put("status", order.getStatus());
                    map.put("rating", order.getRecipientRating());
                    map.put("comment", order.getRecipientComment());
                    map.put("createTime", order.getCreateTime());

                    String rawName = recipient.getUsername();
                    String maskedName = rawName.length() > 1 ? rawName.charAt(0) + "**" : "**";
                    map.put("recipientName", maskedName);

                    String tag = recipient.getUserTag();
                    String tagText = "普通求助市民";
                    if ("ELDERLY".equals(tag)) tagText = "需照顾老人";
                    else if ("DISABLED".equals(tag)) tagText = "残障人士";
                    else if ("SAN_WORKER".equals(tag)) tagText = "环卫工人";
                    map.put("recipientTag", tagText);

                    result.add(map);
                }
            }
        }
        return result;
    }

    private void checkRationQuota(Long userId, Config config) {
        int maxClaims = config.getMaxDailyClaims() != null ? config.getMaxDailyClaims() : 3;
        String today = LocalDate.now().toString();
        String redisKey = "ration:daily:" + userId + ":" + today;

        // Redis 原子计数: INCR + TTL 到次日0点
        Long currentCount = stringRedisTemplate.opsForValue().increment(redisKey);
        if (currentCount != null && currentCount == 1) {
            long secondsUntilMidnight = ChronoUnit.SECONDS.between(
                    LocalDateTime.now(), LocalDate.now().plusDays(1).atStartOfDay());
            stringRedisTemplate.expire(redisKey, secondsUntilMidnight, TimeUnit.SECONDS);
        }

        if (currentCount != null && currentCount > maxClaims) {
            // 回滚 Redis 计数
            stringRedisTemplate.opsForValue().decrement(redisKey);
            throw new BusinessException("灾区配给制: 您今日申领次数已达上限(" + maxClaims + "次), 请明日再试。资源有限，请理解!");
        }

        // DB兜底: 同步更新 sys_user.daily_claim_count
        User user = userService.getById(userId);
        if (user != null) {
            LocalDate lastClaimDate = user.getLastClaimDate() != null
                    ? user.getLastClaimDate().toLocalDate() : null;
            int newCount;
            if (lastClaimDate == null || !lastClaimDate.equals(LocalDate.now())) {
                newCount = 1;
            } else {
                newCount = (user.getDailyClaimCount() != null ? user.getDailyClaimCount() : 0) + 1;
            }
            user.setDailyClaimCount(newCount);
            user.setLastClaimDate(LocalDateTime.now());
            userService.updateById(user);
        }
    }
}