package com.foodbank.module.resource.goods.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.model.vo.MerchantGoodsVO;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IGoodsService extends IService<Goods> {

    /**
     * 安全扣减库存（利用数据库行锁防超卖）
     */
    boolean deductStockSafe(Long goodsId, int num);

    /**
     * 商家分页查询自己的捐赠记录
     */
    Page<MerchantGoodsVO> getMerchantGoodsPage(int pageNum, int pageSize, String goodsName, Byte status, Long merchantId);

    /**
     * 商家撤销未被接管的捐赠物资
     */
    void revokeGoods(Long goodsId, Long merchantId);

    /**
     * 商家开始自行配送 (上锁，防骑手抢单)
     */
    void startSelfDelivery(Long goodsId, Long merchantId);

    /**
     * 商家确认已送达驿站 (核销入库)
     */
    void finishSelfDelivery(Long goodsId, Long merchantId);
}