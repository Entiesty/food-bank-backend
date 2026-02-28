package com.foodbank.module.resource.goods.service;

import com.foodbank.module.resource.goods.entity.Goods;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IGoodsService extends IService<Goods> {

    /**
     * 安全扣减库存（利用数据库行锁防超卖）
     * @param goodsId 物资ID
     * @param num 扣减数量
     * @return true-扣减成功, false-库存不足扣减失败
     */
    boolean deductStockSafe(Long goodsId, int num);
}