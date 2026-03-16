package com.foodbank.module.resource.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.common.utils.UserContext;
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
            goods.setTags(String.join(",", dto.getTags()));
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

        // 2. 生成调度大盘工单 fb_order
        if (dto.getTargetOrderId() != null) {
            // 🚨 核心修复 2：定向直连缝合！复活那个停滞的 SOS 单！
            DispatchOrder targetOrder = dispatchOrderMapper.selectById(dto.getTargetOrderId());
            if (targetOrder != null && targetOrder.getStatus() == 0) {
                targetOrder.setGoodsId(goods.getGoodsId());
                targetOrder.setExceptionReason(null); // 抹除死因
                targetOrder.setStatus((byte) 0); // 保持在待抢单状态

                // 把商铺坐标、具体的南瓜粥名字和数量，统统喂给老人家的需求单
                User merchant = userMapper.selectById(merchantId);
                if (merchant != null) {
                    targetOrder.setSourceLon(merchant.getCurrentLon());
                    targetOrder.setSourceLat(merchant.getCurrentLat());
                }
                targetOrder.setSourceId(merchantId);
                targetOrder.setGoodsName(goods.getGoodsName());
                targetOrder.setGoodsCount(goods.getStock());

                dispatchOrderMapper.updateById(targetOrder);
                log.info("🚨 战时响应：商家 {} 已接管求救单 {}！物资名更新为 {}，系统转入 P2P 直达模式！",
                        merchantId, targetOrder.getOrderSn(), goods.getGoodsName());

                // 【重中之重】：直接 return，坚决不生成废弃的 DON 单！
                return;
            }
        }

        // 只有平时态，且没传 TargetOrderId，才会走到这里生成普通的 DON 单
        DispatchOrder order = new DispatchOrder();
        order.setOrderSn("DON-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        order.setOrderType((byte) 1); // 1-供应单(商家->驿站)
        order.setGoodsId(goods.getGoodsId());
        order.setRequiredCategory(dto.getCategory());
        order.setGoodsName(dto.getGoodsName());
        order.setGoodsCount(dto.getStock());
        order.setSourceId(merchantId);
        order.setDestId(dto.getCurrentStationId());
        order.setDeliveryMethod((byte) 1); // 志愿配送
        order.setUrgencyLevel((byte) 5);
        order.setStatus((byte) 0);

        Station station = stationMapper.selectById(dto.getCurrentStationId());
        if (station != null) {
            order.setTargetLon(station.getLongitude());
            order.setTargetLat(station.getLatitude());
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