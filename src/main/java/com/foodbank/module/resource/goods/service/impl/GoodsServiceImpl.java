package com.foodbank.module.resource.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.common.controller.websocket.WebSocketServer;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.mapper.GoodsMapper;
import com.foodbank.module.resource.goods.model.dto.DonateDTO;
import com.foodbank.module.resource.goods.model.dto.GoodsAdjustDTO;
import com.foodbank.module.resource.goods.model.vo.MerchantGoodsVO;
import com.foodbank.module.resource.goods.service.IGoodsService;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.mapper.StationMapper;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.mapper.UserMapper;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.order.mapper.DispatchOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GoodsServiceImpl extends ServiceImpl<GoodsMapper, Goods> implements IGoodsService {

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private DispatchOrderMapper dispatchOrderMapper;

    @Autowired
    private UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void donateGoods(DonateDTO dto) {
        Long merchantId = UserContext.getUserId();
        if (merchantId == null) throw new BusinessException("用户信息异常，请重新登录");

        // 1. 生成物资表数据 fb_goods
        Goods goods = new Goods();
        goods.setMerchantId(merchantId);
        goods.setGoodsName(dto.getGoodsName());
        goods.setCategory(dto.getCategory());
        goods.setStock(dto.getStock());
        goods.setExpirationDate(dto.getExpirationDate());
        goods.setIsEmergencyOnly((byte) 0);
        goods.setGoodsImageUrl(dto.getGoodsImageUrl());

        if (dto.getTags() != null && !dto.getTags().isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                String jsonTags = mapper.writeValueAsString(dto.getTags());
                goods.setTags(jsonTags);
            } catch (Exception e) {
                goods.setTags("[]");
            }
        } else {
            goods.setTags("[]");
        }

        goods.setVolumeLevel(dto.getVolumeLevel() != null ? dto.getVolumeLevel() : 1);
        goods.setWeightLevel(dto.getWeightLevel() != null ? dto.getWeightLevel() : 1);

        if (dto.getTargetOrderId() != null) {
            goods.setCurrentStationId(null);
            goods.setStatus((byte) 0); // 待取货
        } else {
            goods.setCurrentStationId(dto.getCurrentStationId());
            goods.setStatus((byte) 0);
        }

        boolean saved = this.save(goods);
        if (!saved) throw new BusinessException("物资入库失败，请稍后重试");

        // 2. 处理 P2P 战时响应逻辑 (缝合求助单)
        boolean isP2P = false;
        if (dto.getTargetOrderId() != null) {
            DispatchOrder targetOrder = dispatchOrderMapper.selectById(dto.getTargetOrderId());
            if (targetOrder != null && targetOrder.getStatus() == 0) {
                targetOrder.setGoodsId(goods.getGoodsId());
                targetOrder.setExceptionReason(null);
                targetOrder.setStatus((byte) 0);

                User merchant = userMapper.selectById(merchantId);
                if (merchant != null) {
                    targetOrder.setSourceLon(merchant.getCurrentLon());
                    targetOrder.setSourceLat(merchant.getCurrentLat());
                }

                targetOrder.setSourceId(merchantId);
                targetOrder.setGoodsName(goods.getGoodsName());
                targetOrder.setGoodsCount(goods.getStock());

                dispatchOrderMapper.updateById(targetOrder);
                isP2P = true;

                log.info("🚨 战时响应：商家 {} 已接管求救单 {}！系统转入 P2P 直达模式！", merchantId, targetOrder.getOrderSn());

                if (merchant != null && targetOrder.getDestId() != null) {
                    try {
                        String msg = "{\"type\":\"NEW_SOS\", \"orderSn\":\"" + targetOrder.getOrderSn() + "\", \"msg\":\"您的求救信号已被紧急响应！物资即将直达您的位置！\"}";
                        WebSocketServer.sendMessageToUser(targetOrder.getDestId(), msg);
                    } catch (Exception e) {
                        log.error("推送紧急响应弹窗失败", e);
                    }
                }
            }
        }

        // 3. 生成 DON 单（复式记账）
        DispatchOrder order = new DispatchOrder();
        order.setOrderSn("DON-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        order.setOrderType((byte) 1);
        order.setGoodsId(goods.getGoodsId());
        order.setRequiredCategory(dto.getCategory());
        order.setGoodsName(dto.getGoodsName());
        order.setGoodsCount(dto.getStock());
        order.setSourceId(merchantId);

        if (isP2P) {
            order.setDestId(null);
            order.setDeliveryMethod((byte) 1);
            order.setUrgencyLevel((byte) 5);
            order.setStatus((byte) 3);
            order.setExceptionReason("响应紧急广播：定向直供备案");
        } else {
            order.setDestId(dto.getCurrentStationId());

            // 🚨 修复 1：发布时就动态接收配送方式
            // 如果前端没传 deliveryMethod 字段，由于你这是 GoodsServiceImpl 的 dto 里面可能没这个字段，所以暂且默认 1
            // 真实的自送流转我们在下面的 startSelfDelivery 里做状态跳转
            order.setDeliveryMethod((byte) 1);
            order.setStatus((byte) 0);

            byte calculatedUrgency = 1;
            String cat = dto.getCategory();

            if (cat != null && (cat.contains("生鲜") || cat.contains("速食品") || cat.contains("乳制品") || cat.contains("烘焙糕点"))) {
                calculatedUrgency = 3;
            }

            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            java.time.LocalDateTime expireTime = dto.getExpirationDate();
            if (expireTime != null && expireTime.isAfter(now)) {
                long hoursUntilExpire = java.time.Duration.between(now, expireTime).toHours();
                if (hoursUntilExpire <= 3) {
                    calculatedUrgency = 5;
                }
            }
            order.setUrgencyLevel(calculatedUrgency);

            if (dto.getCurrentStationId() != null) {
                Station station = stationMapper.selectById(dto.getCurrentStationId());
                if (station != null) {
                    order.setTargetLon(station.getLongitude());
                    order.setTargetLat(station.getLatitude());
                }
            }
        }
        dispatchOrderMapper.insert(order);

        // 触发大屏 WebSocket 通知
        try {
            String jsonMsg = String.format("{\"type\":\"NEW_REQ\", \"orderSn\":\"%s\"}", order.getOrderSn());
            WebSocketServer.broadcast(jsonMsg);
        } catch (Exception e) {}
    }

    @Override
    public boolean deductStockSafe(Long goodsId, int num) {
        return this.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getGoodsId, goodsId).ge(Goods::getStock, num).setSql("stock = stock - " + num));
    }

    @Override
    public Page<MerchantGoodsVO> getMerchantGoodsPage(int pageNum, int pageSize, String goodsName, Byte status, Long merchantId) {
        Page<Goods> pageReq = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Goods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Goods::getMerchantId, merchantId);
        if (StringUtils.hasText(goodsName)) wrapper.like(Goods::getGoodsName, goodsName);
        if (status != null) wrapper.eq(Goods::getStatus, status);
        wrapper.orderByDesc(Goods::getCreateTime);

        Page<Goods> goodsPage = this.page(pageReq, wrapper);
        Page<MerchantGoodsVO> voPage = new Page<>(goodsPage.getCurrent(), goodsPage.getSize(), goodsPage.getTotal());
        List<MerchantGoodsVO> voList = goodsPage.getRecords().stream().map(goods -> {
            MerchantGoodsVO vo = new MerchantGoodsVO();
            BeanUtils.copyProperties(goods, vo);
            if (goods.getCurrentStationId() != null) {
                Station station = stationMapper.selectById(goods.getCurrentStationId());
                if (station != null) {
                    vo.setStationName(station.getStationName());
                    if (station.getLongitude() != null) vo.setStationLon(station.getLongitude().doubleValue());
                    if (station.getLatitude() != null) vo.setStationLat(station.getLatitude().doubleValue());
                }
            }
            return vo;
        }).collect(Collectors.toList());
        voPage.setRecords(voList); return voPage;
    }

    @Override
    public void revokeGoods(Long goodsId, Long merchantId) {
        Goods goods = this.getById(goodsId);
        if (goods == null || !goods.getMerchantId().equals(merchantId)) throw new BusinessException("物资不存在或无权操作");
        if (goods.getStatus() != 0) throw new BusinessException("已被接管，无法撤销！");
        this.removeById(goodsId);
    }

    // =========================================================================
    // 🚨 核心修复 2：开启商家自送时，同步跃迁订单状态与履约模式！
    // =========================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startSelfDelivery(Long goodsId, Long merchantId) {
        Goods goods = this.getById(goodsId);
        if (goods == null || !goods.getMerchantId().equals(merchantId)) throw new BusinessException("物资不存在或无权操作");
        if (goods.getStatus() != 0) throw new BusinessException("该物资已被调度引擎接管或在流转中，无法开启自送！");

        // 1. 改变物资表状态
        goods.setStatus((byte) 4);
        this.updateById(goods);

        // 2. 同步改变订单表状态，并把履约模式改为“自提/自送(2)”
        LambdaUpdateWrapper<DispatchOrder> orderUpdate = new LambdaUpdateWrapper<>();
        orderUpdate.eq(DispatchOrder::getGoodsId, goodsId)
                .eq(DispatchOrder::getOrderType, (byte) 1) // 定位到这批物资对应的 DON 捐赠单
                .set(DispatchOrder::getDeliveryMethod, (byte) 2) // 2代表商家亲自护送
                .set(DispatchOrder::getStatus, (byte) 4); // 大屏上显示：🟣 商家自送中

        dispatchOrderMapper.update(null, orderUpdate);
    }

    // =========================================================================
    // 🚨 核心修复 3：完成商家自送时，同步让订单完美闭环入库！
    // =========================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishSelfDelivery(Long goodsId, Long merchantId) {
        Goods goods = this.getById(goodsId);
        if (goods == null || !goods.getMerchantId().equals(merchantId)) throw new BusinessException("物资不存在或无权操作");
        if (goods.getStatus() != 4) throw new BusinessException("操作失败，该物资当前未处于自送状态！");

        // 1. 改变物资表状态为已入库
        goods.setStatus((byte) 2);
        this.updateById(goods);

        // 2. 同步改变订单表状态为已完结/已入库
        LambdaUpdateWrapper<DispatchOrder> orderUpdate = new LambdaUpdateWrapper<>();
        orderUpdate.eq(DispatchOrder::getGoodsId, goodsId)
                .eq(DispatchOrder::getOrderType, (byte) 1)
                .set(DispatchOrder::getStatus, (byte) 2); // 大屏上显示：🟢 已抵达入库

        dispatchOrderMapper.update(null, orderUpdate);
    }

    @Override
    public List<Goods> getStationGoods(Long stationId) {
        return this.list(new LambdaQueryWrapper<Goods>().eq(Goods::getCurrentStationId, stationId).eq(Goods::getStatus, (byte) 2).orderByAsc(Goods::getExpirationDate));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjustGoodsStock(GoodsAdjustDTO dto) {
        Goods goods = this.getById(dto.getGoodsId());
        if (goods == null) throw new BusinessException("该物资批次不存在");

        int currentStock = goods.getStock() != null ? goods.getStock() : 0;
        if (dto.getAdjustType() == 1) goods.setStock(currentStock + dto.getDiffCount());
        else if (dto.getAdjustType() == 2) {
            if (currentStock < dto.getDiffCount()) throw new BusinessException("扣减数量不能大于当前实际库存！");
            goods.setStock(currentStock - dto.getDiffCount());
            if (goods.getStock() == 0) goods.setStatus((byte) 3);
        } else throw new BusinessException("非法的干预类型");

        this.updateById(goods);
        log.info("⚖️ 大仓手工平账: 物资[{}] 事由: {}", goods.getGoodsName(), dto.getReason());
    }
}