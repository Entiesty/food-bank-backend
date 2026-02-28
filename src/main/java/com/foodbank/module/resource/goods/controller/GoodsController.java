package com.foodbank.module.resource.goods.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.common.api.Result;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.service.IGoodsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Tag(name = "Goods Controller", description = "救灾物资与库存管理")
@RestController
@RequestMapping("/resource/goods")
public class GoodsController {

    @Autowired
    private IGoodsService goodsService;

    @Operation(summary = "1. 爱心商家捐赠物资入库", description = "商家录入物资并指定存入哪个据点")
    @PostMapping("/donate")
    public Result<String> donateGoods(@RequestBody Goods goods) {
        Long merchantId = UserContext.getUserId();
        Byte role = UserContext.getUserRole();

        // 鉴权：只有商家(2)或管理员(4)可以入库物资
        if (role == null || (role != 2 && role != 4)) {
            return Result.failed("权限不足：仅限认证商家或管理员操作");
        }

        goods.setMerchantId(merchantId);
        goods.setStatus((byte) 2); // 状态设为 2:已入库可用
        goods.setCreateTime(LocalDateTime.now());

        boolean saved = goodsService.save(goods);
        return saved ? Result.success("感谢您的捐赠！物资已成功入库。") : Result.failed("入库失败");
    }

    @Operation(summary = "2. 分页查询据点可用库存", description = "按据点ID或物资类型过滤查询")
    @GetMapping("/list")
    public Result<Page<Goods>> getGoodsList(
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        LambdaQueryWrapper<Goods> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Goods::getStatus, 2); // 只查可用的
        if (stationId != null) queryWrapper.eq(Goods::getCurrentStationId, stationId);
        if (category != null) queryWrapper.eq(Goods::getCategory, category);

        return Result.success(goodsService.page(new Page<>(pageNum, pageSize), queryWrapper));
    }
}