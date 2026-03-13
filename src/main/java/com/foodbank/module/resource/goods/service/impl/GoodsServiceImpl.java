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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void donateGoods(DonateDTO dto) {
        Long merchantId = UserContext.getUserId();
        if (merchantId == null) {
            throw new BusinessException("用户信息异常，请重新登录");
        }

        // 1. 生成物资表数据 fb_goods，状态锁定为待取货
        Goods goods = new Goods();
        goods.setMerchantId(merchantId);
        goods.setCurrentStationId(dto.getCurrentStationId());
        goods.setGoodsName(dto.getGoodsName());
        goods.setCategory(dto.getCategory());
        goods.setStock(dto.getStock());
        goods.setExpirationDate(dto.getExpirationDate());
        goods.setStatus((byte) 0); // 0-待取货
        goods.setIsEmergencyOnly((byte) 0); // 默认平时可用
        boolean saved = this.save(goods);
        if (!saved) throw new BusinessException("物资入库失败，请稍后重试");

        // 2. 生成调度大盘工单 fb_order
        DispatchOrder order = new DispatchOrder();
        // 生成唯一捐赠单号 DON-
        order.setOrderSn("DON-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        order.setOrderType((byte) 1); // 1-供应单(商家->驿站)
        order.setGoodsId(goods.getGoodsId());
        order.setRequiredCategory(dto.getCategory());
        order.setGoodsName(dto.getGoodsName());
        order.setGoodsCount(dto.getStock());
        order.setSourceId(merchantId);
        order.setDestId(dto.getCurrentStationId());
        order.setDeliveryMethod((byte) 1); // 1-全城志愿配送
        order.setUrgencyLevel((byte) 5); // 捐赠单默认中等紧急度
        order.setStatus((byte) 0); // 0-待匹配

        // 补齐终点坐标(驿站坐标)
        Station station = stationMapper.selectById(dto.getCurrentStationId());
        if (station != null) {
            order.setTargetLon(station.getLongitude());
            order.setTargetLat(station.getLatitude());
        }

        dispatchOrderMapper.insert(order);
    }

    @Override
    public boolean deductStockSafe(Long goodsId, int num) {
        return this.update(new LambdaUpdateWrapper<Goods>()
                .eq(Goods::getGoodsId, goodsId)
                .ge(Goods::getStock, num)
                .setSql("stock = stock - " + num));
    }

    @Override
    public Page<MerchantGoodsVO> getMerchantGoodsPage(int pageNum, int pageSize, String goodsName, Byte status, Long merchantId) {
        Page<Goods> pageReq = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Goods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Goods::getMerchantId, merchantId);

        if (StringUtils.hasText(goodsName)) {
            wrapper.like(Goods::getGoodsName, goodsName);
        }
        if (status != null) {
            wrapper.eq(Goods::getStatus, status);
        }
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

        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public void revokeGoods(Long goodsId, Long merchantId) {
        Goods goods = this.getById(goodsId);
        if (goods == null || !goods.getMerchantId().equals(merchantId)) throw new BusinessException("物资不存在或无权操作");
        if (goods.getStatus() != 0) throw new BusinessException("该物资已被调度引擎接管或运输中，无法撤销！");
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
        // 🚨 核心：只查指定驿站的，并且状态是 2（已入库）的物资
        // 按照保质期升序排列（快过期的排在最前面，方便网格员优先处理）
        return this.list(new LambdaQueryWrapper<Goods>()
                .eq(Goods::getCurrentStationId, stationId)
                .eq(Goods::getStatus, (byte) 2)
                .orderByAsc(Goods::getExpirationDate));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjustGoodsStock(GoodsAdjustDTO dto) {
        Goods goods = this.getById(dto.getGoodsId());
        if (goods == null) {
            throw new BusinessException("该物资批次不存在");
        }

        int currentStock = goods.getStock() != null ? goods.getStock() : 0;

        if (dto.getAdjustType() == 1) {
            // ⬆️ 增加库存 (找回/补录)
            goods.setStock(currentStock + dto.getDiffCount());
        } else if (dto.getAdjustType() == 2) {
            // ⬇️ 扣减库存 (损耗/发放)
            if (currentStock < dto.getDiffCount()) {
                throw new BusinessException("扣减数量不能大于当前实际库存！");
            }
            goods.setStock(currentStock - dto.getDiffCount());

            // 💡 架构师细节：如果库存被扣到了 0，自动将状态置为 3 (已耗尽/已核销)
            // 这样大屏和库存列表就不会再显示这个空批次了
            if (goods.getStock() == 0) {
                goods.setStatus((byte) 3);
            }
        } else {
            throw new BusinessException("非法的干预类型");
        }

        this.updateById(goods);

        log.info("⚖️ 大仓手工平账: 物资[{}] 变动类型[{}], 数量[{}], 当前余量[{}], 事由: {}",
                goods.getGoodsName(), dto.getAdjustType() == 1 ? "入" : "出", dto.getDiffCount(), goods.getStock(), dto.getReason());
    }
}