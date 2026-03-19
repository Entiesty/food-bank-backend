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
    @Transactional(rollbackFor = Exception.class) // 建议加上事务控制，保证库存和订单的数据一致性
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

// 🚨 核心修复：将前端传来的 List<String> 标准序列化为 JSON 数组字符串
        if (dto.getTags() != null && !dto.getTags().isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                String jsonTags = mapper.writeValueAsString(dto.getTags());
                goods.setTags(jsonTags);
            } catch (Exception e) {
                // 如果序列化失败，兜底存入空的 JSON 数组以符合 MySQL 格式校验
                goods.setTags("[]");
            }
        } else {
            // 如果商家没有选任何标签，存入空的 JSON 数组
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

        // 2. 🚨 核心逻辑：处理 P2P 战时响应逻辑 (缝合求助单)
        boolean isP2P = false;
        if (dto.getTargetOrderId() != null) {
            DispatchOrder targetOrder = dispatchOrderMapper.selectById(dto.getTargetOrderId());
            if (targetOrder != null && targetOrder.getStatus() == 0) {
                targetOrder.setGoodsId(goods.getGoodsId());
                targetOrder.setExceptionReason(null); // 抹除之前“库存不足”的异常备注
                targetOrder.setStatus((byte) 0); // 彻底激活：重回待抢单池！

                User merchant = userMapper.selectById(merchantId);
                if (merchant != null) {
                    targetOrder.setSourceLon(merchant.getCurrentLon());
                    targetOrder.setSourceLat(merchant.getCurrentLat());
                }

                targetOrder.setSourceId(merchantId); // 赋予取货起点
                targetOrder.setGoodsName(goods.getGoodsName()); // 更新具体物资名称
                targetOrder.setGoodsCount(goods.getStock());

                dispatchOrderMapper.updateById(targetOrder);
                isP2P = true;

                log.info("🚨 战时响应：商家 {} 已接管求救单 {}！系统转入 P2P 直达模式！", merchantId, targetOrder.getOrderSn());

                // 👇👇👇 🚨 新增：P2P绑定成功瞬间，向受助老人定向发射 WebSocket 喜报弹窗！
                if (merchant != null && targetOrder.getDestId() != null) {
                    try {
                        String msg = "您的求救信号已被【" + merchant.getUsername() + "】紧急响应！骑士已接单，救援物资即将直达您的位置！";
                        WebSocketServer.sendMessageToUser(targetOrder.getDestId(), msg);
                    } catch (Exception e) {
                        log.error("推送紧急响应弹窗失败", e);
                    }
                }
                // 👆👆👆 新增结束
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
            // 定向直供：DON单直接归档，坚决不进骑士大厅去干扰视线
            order.setDestId(null);
            order.setDeliveryMethod((byte) 1);
            order.setUrgencyLevel((byte) 5); // 🚨 P2P 赋予最高优先级
            order.setStatus((byte) 3); // 3 = 已归档
            order.setExceptionReason("响应紧急广播：定向直供备案");
        } else {
            // 平时捐赠态
            order.setDestId(dto.getCurrentStationId());
            order.setDeliveryMethod((byte) 1);
            order.setStatus((byte) 0);

            // 智能紧急度赋分引擎
            byte calculatedUrgency = 1; // 默认作为最低优先级的顺路运单
            String cat = dto.getCategory();

            // 基础分：生鲜、热食、乳制品容易坏，基础紧急度提高到 3
            if (cat != null && (cat.contains("生鲜") || cat.contains("速食品") || cat.contains("乳制品") || cat.contains("烘焙糕点"))) {
                calculatedUrgency = 3;
            }

            // 临期截断分：计算过期倒计时
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            java.time.LocalDateTime expireTime = dto.getExpirationDate();
            if (expireTime != null && expireTime.isAfter(now)) {
                long hoursUntilExpire = java.time.Duration.between(now, expireTime).toHours();
                // 如果距离过期不足 3 小时，不论是什么物资，直接触发“临期抢救机制”，紧急度飙升到 5！
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

    @Override
    public void startSelfDelivery(Long goodsId, Long merchantId) {
        Goods goods = this.getById(goodsId);
        if (goods == null || !goods.getMerchantId().equals(merchantId)) throw new BusinessException("物资不存在或无权操作");
        if (goods.getStatus() != 0) throw new BusinessException("该物资已被调度引擎接管或在流转中，无法开启自送！");
        goods.setStatus((byte) 4);
        this.updateById(goods);
    }

    @Override
    public void finishSelfDelivery(Long goodsId, Long merchantId) {
        Goods goods = this.getById(goodsId);
        if (goods == null || !goods.getMerchantId().equals(merchantId)) throw new BusinessException("物资不存在或无权操作");
        if (goods.getStatus() != 4) throw new BusinessException("操作失败，该物资当前未处于自送状态！");
        goods.setStatus((byte) 2);
        this.updateById(goods);
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