package com.foodbank.module.trade.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
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
import com.foodbank.module.trade.order.model.vo.AvailableOrderVO;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.trade.task.service.IDeliveryTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

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
    public List<DispatchOrder> getPendingOrdersForMap() {
        List<DispatchOrder> list = this.list(new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, (byte) 0)
                .ne(DispatchOrder::getDeliveryMethod, (byte) 2)
                .orderByDesc(DispatchOrder::getCreateTime));
        enrichOrderNames(list);
        return list;
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
    public String publishDemandOrder(DemandPublishDTO dto) {
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) throw new BusinessException("用户信息获取失败，请重新登录");

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
            com.foodbank.module.resource.goods.entity.Goods goods = goodsService.getById(dto.getGoodsId());
            if (goods == null || goods.getStock() < 1) {
                throw new BusinessException("手慢了！该物资已被其他街坊抢空了！");
            }
            goods.setStock(goods.getStock() - 1);
            if (goods.getStock() == 0) goods.setStatus((byte) 3);
            goodsService.updateById(goods);

            String code = String.valueOf((int)((Math.random() * 9 + 1) * 100000));
            dispatchOrder.setPickupCode(code);
            dispatchOrder.setGoodsId(dto.getGoodsId());
            dispatchOrder.setSourceId(dto.getSourceId());
            dispatchOrder.setStatus((byte) 1);
        } else {
            dispatchOrder.setStatus((byte) 0);
        }

        String specificName = dto.getDescription() != null ? dto.getDescription() : dto.getRequiredCategory();
        dispatchOrder.setGoodsName("急需：" + specificName);
        dispatchOrder.setGoodsCount(1);

        boolean saved = this.save(dispatchOrder);
        if (!saved) throw new BusinessException("求助发布失败，请稍后重试");

        try {
            String msgType = dispatchOrder.getOrderSn().startsWith("SOS") ? "NEW_SOS" : "NEW_REQ";
            String jsonMsg = String.format("{\"type\":\"%s\", \"orderSn\":\"%s\"}", msgType, dispatchOrder.getOrderSn());
            com.foodbank.module.common.controller.websocket.WebSocketServer.broadcast(jsonMsg);
        } catch (Exception e) {
            log.error("WebSocket 订单通知广播失败", e);
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
            return AvailableOrderVO.builder()
                    .orderId(order.getOrderId()).orderSn(order.getOrderSn())
                    .goodsName(order.getGoodsName()).goodsCount(order.getGoodsCount())
                    .requiredCategory(order.getRequiredCategory()).urgencyLevel(order.getUrgencyLevel())
                    .sourceName(sourceName).sourceAddress(sourceAddress).sourceLon(sourceLon).sourceLat(sourceLat)
                    .targetName(targetName).targetAddress(targetAddress).targetLon(targetLon).targetLat(targetLat)
                    .createTime(order.getCreateTime()).build();
        }).collect(Collectors.toList());

        Long userId = UserContext.getUserId();
        User volunteer = userService.getById(userId);
        if (volunteer != null) {
            Double volLon = volunteer.getCurrentLon() != null ? volunteer.getCurrentLon().doubleValue() : null;
            Double volLat = volunteer.getCurrentLat() != null ? volunteer.getCurrentLat().doubleValue() : null;
            int creditScore = volunteer.getCreditScore() != null ? volunteer.getCreditScore() : 100;
            dispatchStrategy.rankOrdersForVolunteer(voList, volLon, volLat, creditScore);
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

        // 【库存兜底回滚】
        if (order.getDeliveryMethod() != null && order.getDeliveryMethod() == 2 && order.getGoodsId() != null) {
            com.foodbank.module.resource.goods.entity.Goods goods = goodsService.getById(order.getGoodsId());
            if (goods != null) {
                goods.setStock(goods.getStock() + 1);
                if (goods.getStatus() == 3) goods.setStatus((byte) 2);
                goodsService.updateById(goods);
            }
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
}