package com.foodbank.module.resource.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
import com.foodbank.websocket.WebSocketServer;
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

    @Autowired
    private com.foodbank.module.system.config.service.IConfigService configService;

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
        goods.setIsEmergencyOnly(autoTagEmergency(dto.getCategory()));
        goods.setGoodsImageUrl(dto.getGoodsImageUrl());
        goods.setEstimatedValue(dto.getEstimatedValue() != null ? dto.getEstimatedValue() : java.math.BigDecimal.ZERO);
        goods.setUnit(dto.getUnit() != null ? dto.getUnit() : "件");

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

        // 纯日常捐赠：物资绑定目标驿站
        goods.setCurrentStationId(dto.getCurrentStationId());
        goods.setStatus((byte) 0);
        if (dto.getCurrentStationId() != null) {
            Station targetStation = stationMapper.selectById(dto.getCurrentStationId());
            if (targetStation == null) {
                throw new BusinessException("所选驿站不存在，请刷新页面后重新选择");
            }
        }

        boolean saved = this.save(goods);
        if (!saved) throw new BusinessException("物资入库失败，请稍后重试");

        // 更新商家CSR统计
        updateMerchantCsrStats(merchantId, dto.getStock());

        // 生成 DON 单（日常集散模式）
        DispatchOrder order = new DispatchOrder();
        order.setOrderSn("DON-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        order.setOrderType((byte) 1);
        order.setGoodsId(goods.getGoodsId());
        order.setRequiredCategory(dto.getCategory());
        order.setGoodsName(dto.getGoodsName());
        order.setGoodsCount(dto.getStock());
        order.setSourceId(merchantId);
        order.setDestId(dto.getCurrentStationId());
        order.setDeliveryMethod((byte) 1);
        order.setStatus((byte) 0);

        byte calculatedUrgency = 1;
        String cat = dto.getCategory();
        if (cat != null && (cat.contains("生鲜") || cat.contains("冷冻") || cat.contains("乳制品") || cat.contains("烘焙") || cat.contains("速食") || cat.contains("热食"))) {
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
        LambdaQueryWrapper<Goods> wrapper = new LambdaQueryWrapper<Goods>()
                .eq(Goods::getCurrentStationId, stationId)
                .eq(Goods::getStatus, (byte) 2)
                .orderByAsc(Goods::getExpirationDate);

        // 应急冻结期: 仅展示战备物资
        com.foodbank.module.system.config.entity.Config config = configService.getCurrentConfig();
        if ("WARNING_FREEZE".equals(config.getSysMode())) {
            wrapper.eq(Goods::getIsEmergencyOnly, (byte) 1);
        }

        return this.list(wrapper);
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

    private void updateMerchantCsrStats(Long merchantId, int newStock) {
        User merchant = userMapper.selectById(merchantId);
        if (merchant == null || merchant.getRole() != 2) return;

        int totalDonations = (merchant.getTotalDonations() != null ? merchant.getTotalDonations() : 0) + newStock;
        merchant.setTotalDonations(totalDonations);

        // CSR等级自动递升
        byte csrLevel = 0;
        if (totalDonations >= 2000) csrLevel = 3;
        else if (totalDonations >= 500) csrLevel = 2;
        else if (totalDonations >= 100) csrLevel = 1;
        merchant.setCsrLevel(csrLevel);

        userMapper.updateById(merchant);
        log.info("🏅 商家CSR更新: 商家[{}] 累计捐赠{}件, CSR等级: {}", merchantId, totalDonations, csrLevel);
    }

    /**
     * 按类目自动判定战备物资 — 商家无需手动勾选
     */
    private byte autoTagEmergency(String category) {
        if (category == null) return 0;
        // 应急物资 + 医疗健康 → 自动战备
        if (category.startsWith("应急") || category.equals("常备药品")
                || category.equals("外用急救") || category.equals("医疗器械")
                || category.equals("营养补品")) {
            return 1;
        }
        return 0;
    }
}