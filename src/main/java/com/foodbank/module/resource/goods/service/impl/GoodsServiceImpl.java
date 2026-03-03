package com.foodbank.module.resource.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.mapper.GoodsMapper;
import com.foodbank.module.resource.goods.model.vo.MerchantGoodsVO;
import com.foodbank.module.resource.goods.service.IGoodsService;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.mapper.StationMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class GoodsServiceImpl extends ServiceImpl<GoodsMapper, Goods> implements IGoodsService {

    @Autowired
    private StationMapper stationMapper;

    @Override
    public boolean deductStockSafe(Long goodsId, int num) {
        // 🚨 核心防线：UPDATE fb_goods SET stock = stock - num WHERE goods_id = ? AND stock >= num
        return this.update(new LambdaUpdateWrapper<Goods>()
                .eq(Goods::getGoodsId, goodsId)
                .ge(Goods::getStock, num) // 必须保证当前库存 >= 扣减数
                .setSql("stock = stock - " + num));
    }

    @Override
    public Page<MerchantGoodsVO> getMerchantGoodsPage(int pageNum, int pageSize, String goodsName, Byte status, Long merchantId) {
        Page<Goods> pageReq = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Goods> wrapper = new LambdaQueryWrapper<>();

        // 只能查自己的
        wrapper.eq(Goods::getMerchantId, merchantId);

        // 动态查询条件
        if (StringUtils.hasText(goodsName)) {
            wrapper.like(Goods::getGoodsName, goodsName);
        }
        if (status != null) {
            wrapper.eq(Goods::getStatus, status);
        }
        wrapper.orderByDesc(Goods::getCreateTime);

        Page<Goods> goodsPage = this.page(pageReq, wrapper);

        // 将 Entity 转换为 VO，并填充 StationName
        Page<MerchantGoodsVO> voPage = new Page<>(goodsPage.getCurrent(), goodsPage.getSize(), goodsPage.getTotal());
        List<MerchantGoodsVO> voList = goodsPage.getRecords().stream().map(goods -> {
            MerchantGoodsVO vo = new MerchantGoodsVO();
            BeanUtils.copyProperties(goods, vo);

            // 查询驿站名称
            if (goods.getCurrentStationId() != null) {
                Station station = stationMapper.selectById(goods.getCurrentStationId());
                if (station != null) {
                    vo.setStationName(station.getStationName());
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
        if (goods == null || !goods.getMerchantId().equals(merchantId)) {
            throw new BusinessException("物资不存在或无权操作");
        }

        // 🚨 核心风控：如果物资状态不是 0（待取货），绝对不让删！
        if (goods.getStatus() != 0) {
            throw new BusinessException("该物资已被调度引擎接管或运输中，无法撤销！");
        }

        // 物理删除（没进流通的数据直接删）
        this.removeById(goodsId);
    }
}