package com.foodbank.module.goods.service.impl;

import com.foodbank.module.goods.entity.Goods;
import com.foodbank.module.goods.mapper.GoodsMapper;
import com.foodbank.module.goods.service.IGoodsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 物资库存与流转表 服务实现类
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Service
public class GoodsServiceImpl extends ServiceImpl<GoodsMapper, Goods> implements IGoodsService {

}
