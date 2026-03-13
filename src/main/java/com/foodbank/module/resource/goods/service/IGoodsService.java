package com.foodbank.module.resource.goods.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.model.dto.DonateDTO;
import com.foodbank.module.resource.goods.model.dto.GoodsAdjustDTO;
import com.foodbank.module.resource.goods.model.vo.MerchantGoodsVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

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

    void donateGoods(DonateDTO dto);

    /**
     * 🚀 获取指定驿站当前在库的全部物资（状态为 2:已入库）
     */
    List<Goods> getStationGoods(Long stationId);

    /**
     * 🚀 线下手工干预/校准物资库存
     */
    void adjustGoodsStock(GoodsAdjustDTO dto);
}