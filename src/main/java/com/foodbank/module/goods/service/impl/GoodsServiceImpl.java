package com.foodbank.module.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.foodbank.module.goods.entity.Goods;
import com.foodbank.module.goods.mapper.GoodsMapper;
import com.foodbank.module.goods.service.IGoodsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class GoodsServiceImpl extends ServiceImpl<GoodsMapper, Goods> implements IGoodsService {

    @Override
    public boolean deductStockSafe(Long goodsId, int num) {
        // 🚨 核心防线：UPDATE fb_goods SET stock = stock - num WHERE goods_id = ? AND stock >= num
        // 这个操作在 MySQL InnoDB 引擎下是原子的，天然防超卖
        return this.update(new LambdaUpdateWrapper<Goods>()
                .eq(Goods::getGoodsId, goodsId)
                .ge(Goods::getStock, num) // 必须保证当前库存 >= 扣减数
                .setSql("stock = stock - " + num));
    }
}